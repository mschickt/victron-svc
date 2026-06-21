package de.mhome.victron.boundary;

import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import de.mhome.victron.config.BleBlacklist;
import de.mhome.victron.config.BleKnownNames;
import de.mhome.victron.config.CompanyIdentifiers;
import de.mhome.victron.config.DeviceConfig;
import de.mhome.victron.config.DeviceRegistry;
import de.mhome.victron.control.ReadingRepository;
import de.mhome.victron.entity.BleScanReading;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sicht auf alle aktuell per BLE sichtbaren Geräte (nicht nur die konfigurierten),
 * für das Setup-Tooling ({@code /api/victron/ble/devices}) und den periodischen
 * Inventory-Scan unten. Die Blacklist ({@link BleBlacklist}) wird hier IMMER
 * angewendet — es gibt keinen Modus, der sie umgeht.
 */
@ApplicationScoped
public class BleInventory {

    private static final Logger LOG = Logger.getLogger(BleInventory.class);

    @Inject VictronBleScanner scanner;
    @Inject DeviceRegistry deviceRegistry;
    @Inject BleBlacklist blacklist;
    @Inject BleKnownNames knownNames;
    @Inject CompanyIdentifiers companyIdentifiers;
    @Inject MeterRegistry registry;
    @Inject ReadingRepository readingRepository;

    private final AtomicInteger lastScanDeviceCount = new AtomicInteger(0);

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("victron_ble_inventory_devices", lastScanDeviceCount, AtomicInteger::get)
            .description("Anzahl sichtbarer BLE-Geräte beim letzten periodischen Inventory-Scan (nach Blacklist-Filter)")
            .register(registry);
    }

    /**
     * Alle aktuell sichtbaren, nicht geblacklisteten BLE-Geräte — dedupliziert nach
     * MAC (BlueZ liefert dieselbe Adresse teils mehrfach als eigenständiges Objekt,
     * z. B. nach Re-Discovery) und nach Name sortiert.
     */
    public List<BleDevice> snapshot() {
        var dm = scanner.deviceManager();
        if (dm == null) return List.of();

        var devices = dm.getDevices(true);
        if (devices == null) return List.of();

        // Defensive Kopie: der BLE-Scanner mutiert dieselbe Liste nebenläufig
        // (BlueZ-Discovery-Callback). Ohne Kopie führt das hier zu sporadischen
        // ConcurrentModificationException/NPE (genullte Listeneinträge) unter Last.
        List<BluetoothDevice> snap;
        try {
            snap = new ArrayList<>(devices);
        } catch (java.util.ConcurrentModificationException e) {
            return List.of();
        }

        var configuredNames = deviceRegistry.devices().stream()
            .collect(java.util.stream.Collectors.toMap(d -> normalize(d.mac()), DeviceConfig::name));

        var byMac = new LinkedHashMap<String, BleDevice>();
        for (BluetoothDevice d : snap) {
            if (d == null || d.getAddress() == null) continue;
            if (blacklist.isBlacklisted(d.getAddress(), d.getName())) continue; // Blacklist gilt immer

            String mac = d.getAddress();
            String macNorm = normalize(mac);
            var mfData = d.getManufacturerData();
            var mfIds = mfData == null ? List.<String>of() :
                mfData.keySet().stream()
                    .map(k -> {
                        String hex = "0x" + String.format("%04X", k.intValue());
                        String name = companyIdentifiers.nameOf(k.intValue());
                        return name != null ? hex + " (" + name + ")" : hex;
                    })
                    .toList();
            String displayName = configuredNames.get(macNorm);
            if (displayName == null) displayName = knownNames.nameFor(mac);
            if (displayName == null) displayName = d.getName();
            var candidate = new BleDevice(
                mac,
                displayName,
                d.getRssi() != null ? d.getRssi().intValue() : null,
                mfIds,
                configuredNames.containsKey(macNorm)
            );

            // Pro normalisierter MAC nur den Eintrag mit den meisten Informationen
            // (Name + Manufacturer-IDs) behalten.
            byMac.merge(macNorm, candidate, (existing, fresh) ->
                richness(fresh) >= richness(existing) ? fresh : existing);
        }

        return byMac.values().stream()
            .sorted(java.util.Comparator.comparing(
                b -> b.name() != null ? b.name() : "",
                String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    @Scheduled(every = "{victron.ble.inventory-interval:5m}")
    void logInventory() {
        var devices = snapshot();
        lastScanDeviceCount.set(devices.size());
        LOG.infof("BLE Inventory-Scan: %d Gerät(e) sichtbar (Blacklist gefiltert)", devices.size());

        Instant now = Instant.now();
        for (BleDevice d : devices) {
            LOG.infof("  • %-17s %-30s RSSI=%-6s Hersteller=%s Konfiguriert=%s",
                d.mac(),
                d.name() != null ? d.name() : "—",
                d.rssiDbm() != null ? d.rssiDbm() + "dBm" : "—",
                d.manufacturerIds().isEmpty() ? "—" : String.join(", ", d.manufacturerIds()),
                d.configured());

            readingRepository.insert(new BleScanReading(
                d.mac(), d.name(), now, d.rssiDbm(), d.manufacturerIds(), d.configured()));
        }
    }

    private static int richness(BleDevice d) {
        return (d.name() != null ? 1 : 0) + d.manufacturerIds().size();
    }

    private static String normalize(String mac) {
        return mac.toUpperCase().replaceAll("[^A-F0-9]", "");
    }

    public record BleDevice(
        String mac,
        String name,
        Integer rssiDbm,
        List<String> manufacturerIds,
        boolean configured
    ) {}
}
