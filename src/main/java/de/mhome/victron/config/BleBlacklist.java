package de.mhome.victron.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Filtert BLE-Geräte aus der Discovery-Liste ({@code /api/victron/ble/devices}) anhand
 * von Name-Präfix oder MAC. Konfiguriert über {@code victron.ble.blacklist-names} /
 * {@code victron.ble.blacklist-macs} (kommagetrennt).
 */
@ApplicationScoped
public class BleBlacklist {

    @Inject
    VictronConfig config;

    private List<String> namePrefixes;
    private List<String> macs;

    private List<String> namePrefixes() {
        if (namePrefixes == null) {
            namePrefixes = parseCsv(config.ble().blacklistNames()).stream()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .toList();
        }
        return namePrefixes;
    }

    private List<String> macs() {
        if (macs == null) {
            macs = parseCsv(config.ble().blacklistMacs()).stream()
                .map(BleBlacklist::normalizeMac)
                .toList();
        }
        return macs;
    }

    public boolean isBlacklisted(String mac, String name) {
        if (mac != null && macs().contains(normalizeMac(mac))) return true;
        if (name != null) {
            String upper = name.toUpperCase(Locale.ROOT);
            for (String prefix : namePrefixes()) {
                if (!prefix.isEmpty() && upper.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private static String normalizeMac(String mac) {
        return mac.toUpperCase(Locale.ROOT).replaceAll("[^A-F0-9]", "");
    }
}
