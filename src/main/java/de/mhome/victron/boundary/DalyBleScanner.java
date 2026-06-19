package de.mhome.victron.boundary;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattService;
import de.mhome.victron.config.DeviceConfig;
import de.mhome.victron.config.DeviceRegistry;
import de.mhome.victron.config.Vendor;
import de.mhome.victron.control.DalyBmsDecoder;
import de.mhome.victron.control.DeviceStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pollt Bulltron-Batterien (Daly Smart BMS) über eine aktive GATT-Verbindung.
 *
 * <p>Anders als {@link VictronBleScanner} (passives Advertisement-Lesen) baut diese
 * Boundary eine Verbindung auf, abonniert die Notify-Charakteristik und schreibt
 * sequentiell die Daly-Requestframes; die Antworten kommen als BlueZ
 * {@code PropertiesChanged}-Signale auf der {@code Value}-Property zurück.
 *
 * <p>Der {@link DeviceManager} (und damit die laufende BLE-Discovery) wird mit
 * {@link VictronBleScanner} geteilt — nur so taucht das Gerät in
 * {@code getDevices()} auf und ist verbindbar.
 *
 * <p>Pro Gerät ist immer nur EINE BLE-Verbindung möglich: solange die Bulltron-App
 * verbunden ist, schlägt das Polling hier fehl (und umgekehrt).
 */
@ApplicationScoped
public class DalyBleScanner {

    private static final Logger LOG = Logger.getLogger(DalyBleScanner.class);

    /** Wartezeit auf eine einzelne Notify-Antwort nach dem Request. */
    private static final long RESPONSE_TIMEOUT_MS = 1200;
    /** Wartezeit auf Auflösung der GATT-Services nach connect(). */
    private static final long SERVICES_TIMEOUT_MS = 8000;
    /** Nach dem Anhalten der Discovery kurz warten, damit der Controller wirklich aufhört zu scannen. */
    private static final long DISCOVERY_SETTLE_MS = 250;
    /** Verbindungsversuche, bevor der Zyklus aufgegeben wird (BlueZ bricht den ersten LE-Connect gern ab). */
    private static final int  CONNECT_ATTEMPTS = 3;
    /** Pause zwischen Verbindungsversuchen. */
    private static final long CONNECT_RETRY_DELAY_MS = 400;

    @Inject DeviceRegistry deviceRegistry;
    @Inject DeviceStore store;
    @Inject DalyBmsDecoder decoder;
    @Inject VictronBleScanner victronScanner;

    // Notify-Charakteristik-Pfad (D-Bus) → Queue der eingehenden Frames für dieses Gerät.
    private final Map<String, BlockingQueue<byte[]>> queuesByCharPath = new ConcurrentHashMap<>();
    private volatile boolean notifyHandlerRegistered = false;

    /**
     * BlueZ liefert Notify-Werte als {@code PropertiesChanged} auf der {@code Value}-Property
     * der Charakteristik. Signal-Pfad == D-Bus-Pfad der Charakteristik → Zuordnung zum Gerät.
     */
    private final class ValueHandler extends AbstractPropertiesChangedHandler {
        @Override
        public void handle(Properties.PropertiesChanged signal) {
            Variant<?> value = signal.getPropertiesChanged().get("Value");
            if (value == null) return;
            BlockingQueue<byte[]> q = queuesByCharPath.get(signal.getPath());
            if (q == null) return; // Signal einer anderen (nicht von uns abonnierten) Charakteristik
            if (value.getValue() instanceof byte[] bytes) {
                q.offer(bytes);
            }
        }
    }

    @Scheduled(every = "{victron.ble.battery-poll-interval:30s}")
    void poll() {
        var bulltronDevices = deviceRegistry.devices().stream()
            .filter(d -> d.vendor() == Vendor.BULLTRON)
            .toList();
        if (bulltronDevices.isEmpty()) return;

        DeviceManager dm = victronScanner.deviceManager();
        if (dm == null) return; // BLE-Stack noch nicht initialisiert

        if (!ensureNotifyHandler(dm)) return;

        // Discovery anhalten: aktives Scannen lässt connect() sonst mit
        // "le-connection-abort-by-local" abbrechen. Nach dem Pollen wieder aufnehmen.
        victronScanner.pauseDiscovery();
        try {
            Thread.sleep(DISCOVERY_SETTLE_MS);
            for (DeviceConfig dc : bulltronDevices) {
                try {
                    pollDevice(dm, dc);
                } catch (Exception e) {
                    LOG.warnf("Bulltron-Polling fehlgeschlagen für %s (%s): %s", dc.mac(), dc.name(), e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            victronScanner.resumeDiscovery();
        }
    }

    private synchronized boolean ensureNotifyHandler(DeviceManager dm) {
        if (notifyHandlerRegistered) return true;
        try {
            dm.registerPropertyHandler(new ValueHandler());
            notifyHandlerRegistered = true;
            LOG.info("Daly Notify-Handler registriert");
            return true;
        } catch (Exception e) {
            LOG.errorf("Registrierung des Daly Notify-Handlers fehlgeschlagen: %s", e.getMessage());
            return false;
        }
    }

    private void pollDevice(DeviceManager dm, DeviceConfig dc) throws Exception {
        BluetoothDevice device = findDevice(dm, dc.mac());
        if (device == null) {
            LOG.debugf("Bulltron %s noch nicht in Discovery sichtbar — übersprungen", dc.mac());
            return;
        }

        if (!Boolean.TRUE.equals(device.isConnected())) {
            LOG.infof("Verbinde mit Bulltron %s (%s)…", dc.mac(), dc.name());
            if (!connectWithRetry(device, dc.mac())) {
                LOG.warnf("connect() zu %s nach %d Versuchen fehlgeschlagen", dc.mac(), CONNECT_ATTEMPTS);
                return;
            }
        }
        if (!awaitServicesResolved(device)) {
            LOG.warnf("GATT-Services von %s nicht aufgelöst — überspringe Zyklus", dc.mac());
            return;
        }

        BluetoothGattService svc = device.getGattServiceByUuid(DalyBmsDecoder.SERVICE_UUID);
        if (svc == null) {
            LOG.warnf("Service %s auf %s nicht gefunden — falsches BMS-Modell? GATT-Profil:",
                DalyBmsDecoder.SERVICE_UUID, dc.mac());
            dumpGattProfile(device);
            return;
        }
        BluetoothGattCharacteristic notifyChar = svc.getGattCharacteristicByUuid(DalyBmsDecoder.NOTIFY_UUID);
        BluetoothGattCharacteristic writeChar  = svc.getGattCharacteristicByUuid(DalyBmsDecoder.WRITE_UUID);
        if (notifyChar == null || writeChar == null) {
            LOG.warnf("Notify/Write-Charakteristik auf %s nicht gefunden", dc.mac());
            return;
        }

        BlockingQueue<byte[]> queue = queuesByCharPath.computeIfAbsent(
            notifyChar.getDbusPath(), p -> new LinkedBlockingQueue<>());
        if (!Boolean.TRUE.equals(notifyChar.isNotifying())) {
            notifyChar.startNotify();
        }

        Map<Integer, byte[]> dataByCommand = new HashMap<>();
        Map<String, Object> writeOpts = new HashMap<>();
        for (int command : DalyBmsDecoder.POLL_COMMANDS) {
            queue.clear(); // alte/unerwartete Frames verwerfen
            writeChar.writeValue(decoder.request(command), writeOpts);

            byte[] frame = queue.poll(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            byte[] payload = (frame == null) ? null : decoder.payload(frame, command);
            if (payload == null) {
                LOG.debugf("Keine/ungültige Antwort auf Command 0x%02X von %s", command, dc.mac());
                continue;
            }
            dataByCommand.put(command, payload);
        }

        if (dataByCommand.isEmpty()) {
            LOG.warnf("Bulltron %s lieferte keine gültigen Frames", dc.mac());
            return;
        }

        var data = decoder.decode(dc.mac(), dc.name(), dataByCommand, Instant.now());
        store.updateBattery(data);
        LOG.infof("Bulltron %s: %s", dc.name(), data);
    }

    /**
     * Versucht mehrfach zu verbinden. BlueZ bricht den ersten LE-Connect nach dem Stoppen der
     * Discovery gelegentlich noch mit {@code le-connection-abort-by-local} ab; ein zweiter Versuch
     * gelingt dann meist.
     */
    private boolean connectWithRetry(BluetoothDevice device, String mac) throws InterruptedException {
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                if (device.connect()) return true;
                LOG.debugf("connect() zu %s lieferte false (Versuch %d/%d)", mac, attempt, CONNECT_ATTEMPTS);
            } catch (Exception e) {
                LOG.debugf("connect() zu %s fehlgeschlagen (Versuch %d/%d): %s", mac, attempt, CONNECT_ATTEMPTS, e.getMessage());
            }
            if (attempt < CONNECT_ATTEMPTS) Thread.sleep(CONNECT_RETRY_DELAY_MS);
        }
        return false;
    }

    /** Loggt alle GATT-Services und ihre Charakteristiken — Diagnose bei unbekanntem BMS-Layout. */
    private void dumpGattProfile(BluetoothDevice device) {
        try {
            var services = device.getGattServices();
            if (services == null || services.isEmpty()) {
                LOG.warn("  (keine GATT-Services aufgelöst)");
                return;
            }
            for (BluetoothGattService s : services) {
                var chars = s.getGattCharacteristics();
                StringBuilder sb = new StringBuilder();
                if (chars != null) {
                    for (BluetoothGattCharacteristic c : chars) {
                        sb.append(' ').append(c.getUuid());
                    }
                }
                LOG.infof("  Service %s → Chars:%s", s.getUuid(), sb);
            }
        } catch (Exception e) {
            LOG.warnf("  GATT-Profil konnte nicht ausgelesen werden: %s", e.getMessage());
        }
    }

    private BluetoothDevice findDevice(DeviceManager dm, String mac) {
        String target = normalize(mac);
        var devices = dm.getDevices(true);
        if (devices == null) return null;
        for (BluetoothDevice d : devices) {
            if (d.getAddress() != null && normalize(d.getAddress()).equals(target)) return d;
        }
        return null;
    }

    /** Wartet (bis {@link #SERVICES_TIMEOUT_MS}) auf {@code ServicesResolved}. */
    private boolean awaitServicesResolved(BluetoothDevice device) throws InterruptedException {
        long deadline = System.nanoTime() + SERVICES_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(device.isServicesResolved())) return true;
            Thread.sleep(150);
        }
        return Boolean.TRUE.equals(device.isServicesResolved());
    }

    private static String normalize(String mac) {
        return mac.toUpperCase().replaceAll("[^A-F0-9]", "");
    }
}
