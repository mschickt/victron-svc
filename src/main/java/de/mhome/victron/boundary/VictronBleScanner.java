package de.mhome.victron.boundary;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import de.mhome.victron.config.VictronConfig;
import de.mhome.victron.control.MpptDecoder;
import de.mhome.victron.control.OrionDecoder;
import de.mhome.victron.control.SmartShuntDecoder;
import de.mhome.victron.control.AesCtrDecryptor;
import de.mhome.victron.control.RecordType;
import de.mhome.victron.control.DeviceStore;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.Variant;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class VictronBleScanner {

    private static final Logger LOG = Logger.getLogger(VictronBleScanner.class);

    // Victron Manufacturer ID (little-endian 0x02E1)
    private static final UInt16 VICTRON_MANUFACTURER_ID = new UInt16(0x02E1);

    @Inject VictronConfig config;
    @Inject DeviceStore store;
    @Inject AesCtrDecryptor decryptor;
    @Inject MpptDecoder mpptDecoder;
    @Inject SmartShuntDecoder shuntDecoder;
    @Inject OrionDecoder orionDecoder;

    private DeviceManager deviceManager;
    private BluetoothAdapter adapter;
    private volatile boolean discovering = false;
    // Scanning ist standardmäßig AUS und wird per REST-API aktiviert (victron.ble.auto-start=true überschreibt dies).
    private volatile boolean scanEnabled = false;

    void onStart(@Observes StartupEvent ev) {
        LOG.infof("Victron BLE Konfiguration: Adapter=%s, Scan-Intervall=%s, auto-start=%s",
            config.ble().adapter(), config.ble().scanInterval(), config.ble().autoStart());
        LOG.infof("Erlaubte Geräte (%d) — nur diese MACs werden verarbeitet:", config.devices().size());
        for (var d : config.devices()) {
            LOG.infof("  • %s  %-20s [%s]", d.mac(), d.name(), d.type());
        }

        try {
            deviceManager = DeviceManager.createInstance(false);
        } catch (Exception e) {
            LOG.error("BLE DeviceManager Initialisierung fehlgeschlagen", e);
            return;
        }
        if (config.ble().autoStart()) {
            LOG.info("auto-start aktiv: starte BLE Scanning");
            enableScanning();
        } else {
            LOG.info("BLE Scanning deaktiviert; per API aktivierbar via POST /api/victron/scan/start");
        }
    }

    /**
     * Aktiviert das periodische Scanning und startet die BLE-Discovery sofort.
     * @return {@code true} wenn die Discovery läuft.
     */
    public synchronized boolean enableScanning() {
        scanEnabled = true;
        return initDiscovery();
    }

    /** Deaktiviert das Scanning und stoppt die laufende BLE-Discovery. */
    public synchronized void disableScanning() {
        scanEnabled = false;
        if (adapter != null && discovering) {
            try {
                adapter.stopDiscovery();
            } catch (Exception e) {
                LOG.warnf("Stoppen der BLE-Discovery fehlgeschlagen: %s", e.getMessage());
            }
        }
        discovering = false;
        LOG.info("BLE Scanning deaktiviert");
    }

    public boolean isScanningEnabled() {
        return scanEnabled;
    }

    /** True wenn die BLE-Discovery aktuell läuft (Adapter bereit und gestartet). */
    public boolean isDiscovering() {
        return discovering;
    }

    /**
     * Richtet den Adapter ein und startet BLE-Discovery. Idempotent und gefahrlos
     * wiederholbar: Bei Erfolg wird {@code discovering} gesetzt, bei Fehlschlag bleibt
     * es {@code false}, sodass {@link #scan()} es beim nächsten Intervall erneut versucht.
     *
     * @return {@code true} wenn Discovery läuft.
     */
    private synchronized boolean initDiscovery() {
        if (discovering) return true;
        if (deviceManager == null) return false;
        try {
            adapter = deviceManager.getAdapter(config.ble().adapter());
            if (adapter == null) {
                LOG.errorf("Bluetooth Adapter '%s' nicht gefunden!", config.ble().adapter());
                return false;
            }

            // Adapter einschalten, falls DOWN (entspricht `hciconfig hciX up`).
            // Hinweis: ein rfkill-Softblock kann hierüber NICHT aufgehoben werden.
            if (!adapter.isPowered()) {
                adapter.setPowered(true);
            }

            // BLE-only Discovery
            Map<String, Variant<?>> filter = new LinkedHashMap<>();
            filter.put("Transport", new Variant<>("le"));
            adapter.setDiscoveryFilter(filter);

            adapter.startDiscovery();
            discovering = true;
            LOG.infof("BLE Discovery gestartet auf %s", config.ble().adapter());
            return true;

        } catch (Exception e) {
            LOG.errorf("BLE Initialisierung fehlgeschlagen, wird erneut versucht: %s", e.getMessage());
            return false;
        }
    }

    @Scheduled(every = "{victron.ble.scan-interval}")
    void scan() {
        if (!scanEnabled) return;
        if (!discovering && !initDiscovery()) return;

        try {
            var devices = deviceManager.getDevices(true);
            if (devices == null) return;

            // Bekannte Device-Configs nach MAC indizieren
            Map<String, VictronConfig.DeviceConfig> configByMac = new HashMap<>();
            for (var dc : config.devices()) {
                configByMac.put(normalize(dc.mac()), dc);
            }

            for (BluetoothDevice device : devices) {
                String mac = normalize(device.getAddress());
                VictronConfig.DeviceConfig dc = configByMac.get(mac);
                if (dc == null) continue; // nicht konfiguriertes Gerät

                Map<UInt16, byte[]> mfData = device.getManufacturerData();
                if (mfData == null || !mfData.containsKey(VICTRON_MANUFACTURER_ID)) continue;

                byte[] raw = mfData.get(VICTRON_MANUFACTURER_ID);
                processAdvertisement(dc, raw);
            }

        } catch (Exception e) {
            LOG.warnf("BLE Scan Fehler: %s", e.getMessage());
            // Discovery evtl. abgerissen (Adapter weg/zurückgesetzt) -> beim nächsten Intervall neu initialisieren.
            discovering = false;
        }
    }

    private void processAdvertisement(VictronConfig.DeviceConfig dc, byte[] raw) {
        if (raw == null || raw.length < 4) return;

        // raw[0]   = Record Type
        // raw[1-2] = Nonce (16-bit, little-endian)
        // raw[3+]  = Encrypted Payload
        int recordTypeId  = raw[0] & 0xFF;
        int nonce         = (raw[1] & 0xFF) | ((raw[2] & 0xFF) << 8);
        byte[] encrypted  = java.util.Arrays.copyOfRange(raw, 3, raw.length);

        var recordType = RecordType.fromId(recordTypeId);
        if (recordType.isEmpty()) {
            LOG.debugf("Unbekannter Record Type 0x%02X für %s", recordTypeId, dc.mac());
            return;
        }

        byte[] decrypted;
        try {
            decrypted = decryptor.decrypt(encrypted, nonce, dc.advertisementKey());
        } catch (Exception e) {
            LOG.warnf("Entschlüsselung fehlgeschlagen für %s: %s", dc.mac(), e.getMessage());
            return;
        }

        switch (recordType.get()) {
            case SOLAR_CHARGER -> {
                var data = mpptDecoder.decode(dc.mac(), dc.name(), decrypted);
                store.updateMppt(data);
                LOG.infof("MPPT %s: %s", dc.name(), data);
            }
            case BATTERY_MONITOR -> {
                var data = shuntDecoder.decode(dc.mac(), dc.name(), decrypted);
                store.updateShunt(data);
                LOG.infof("SmartShunt %s: %s", dc.name(), data);
            }
            case DC_DC_CONVERTER -> {
                var data = orionDecoder.decode(dc.mac(), dc.name(), decrypted);
                store.updateOrion(data);
                LOG.infof("Orion %s: %s", dc.name(), data);
            }
            default -> LOG.debugf("Record Type %s nicht verarbeitet", recordType.get());
        }
    }

    private String normalize(String mac) {
        return mac.toUpperCase().replaceAll("[^A-F0-9]", "");
    }
}
