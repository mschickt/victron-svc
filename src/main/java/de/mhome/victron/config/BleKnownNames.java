package de.mhome.victron.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Anzeigenamen für bekannte, aber nicht konfigurierte BLE-Geräte (z. B. AirTags),
 * konfiguriert über {@code victron.ble.known-names} (kommagetrennte
 * {@code MAC=Name}-Paare). Überschreibt nur den in der Inventory-Liste
 * angezeigten Namen — das Gerät wird weder gepollt noch geblacklistet.
 */
@ApplicationScoped
public class BleKnownNames {

    @Inject
    VictronConfig config;

    private Map<String, String> names;

    private Map<String, String> names() {
        if (names == null) {
            Map<String, String> map = new LinkedHashMap<>();
            for (String entry : parseCsv(config.ble().knownNames())) {
                int idx = entry.indexOf('=');
                if (idx <= 0 || idx == entry.length() - 1) continue;
                String mac = normalizeMac(entry.substring(0, idx).trim());
                String name = entry.substring(idx + 1).trim();
                if (!mac.isEmpty() && !name.isEmpty()) map.put(mac, name);
            }
            names = map;
        }
        return names;
    }

    public String nameFor(String mac) {
        return mac == null ? null : names().get(normalizeMac(mac));
    }

    private static java.util.List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return java.util.List.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private static String normalizeMac(String mac) {
        return mac.toUpperCase(Locale.ROOT).replaceAll("[^A-F0-9]", "");
    }
}
