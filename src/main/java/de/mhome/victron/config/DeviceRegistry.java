package de.mhome.victron.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Liest die Geräteliste aus den Umgebungsvariablen {@code VICTRON_DEVICES_<n>_*}
 * (geliefert über die {@code .env}-Datei, siehe {@code .env.example}).
 *
 * <p>Bewusst NICHT über {@code @ConfigMapping List<...>}: SmallRye kann die
 * Größe einer Liste nicht aus Umgebungsvariablen ableiten („indexed properties
 * aren't supported with environment variables"). Stattdessen werden die Indizes
 * 0,1,2,… per MicroProfile {@link Config} einzeln abgefragt, bis kein {@code MAC}
 * mehr existiert — das funktioniert mit jeder Config-Quelle (Env, .env,
 * application.yml, System-Properties).
 */
@ApplicationScoped
public class DeviceRegistry {

    private static final Logger LOG = Logger.getLogger(DeviceRegistry.class);

    @Inject
    Config config;

    private final List<DeviceConfig> devices = new ArrayList<>();

    @PostConstruct
    void load() {
        for (int i = 0; ; i++) {
            String prefix = "VICTRON_DEVICES_" + i + "_";
            var mac = config.getOptionalValue(prefix + "MAC", String.class);
            if (mac.isEmpty()) {
                break; // erster fehlender Index beendet die Liste
            }

            String name      = config.getOptionalValue(prefix + "NAME",   String.class).orElse(mac.get());
            String typeRaw   = config.getOptionalValue(prefix + "TYPE",   String.class).orElse("MPPT");
            String vendorRaw = config.getOptionalValue(prefix + "VENDOR", String.class).orElse("VICTRON");
            String key       = config.getOptionalValue(prefix + "ADVERTISEMENT_KEY", String.class).orElse("");

            DeviceType type;
            try {
                type = DeviceType.valueOf(typeRaw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                LOG.errorf("Gerät %d (%s): ungültiger TYPE '%s' — übersprungen (erlaubt: %s)",
                    i, mac.get(), typeRaw, java.util.Arrays.toString(DeviceType.values()));
                continue;
            }

            Vendor vendor;
            try {
                vendor = Vendor.valueOf(vendorRaw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                LOG.warnf("Gerät %d (%s): unbekannter VENDOR '%s' — verwende VICTRON (erlaubt: %s)",
                    i, mac.get(), vendorRaw, java.util.Arrays.toString(Vendor.values()));
                vendor = Vendor.VICTRON;
            }

            devices.add(new DeviceConfig(mac.get().trim(), name.trim(), vendor, type, key.trim()));
        }
    }

    /** Unveränderliche Liste der konfigurierten Geräte (Reihenfolge = Index). */
    public List<DeviceConfig> devices() {
        return Collections.unmodifiableList(devices);
    }
}
