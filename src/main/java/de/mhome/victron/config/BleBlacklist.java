package de.mhome.victron.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Filtert BLE-Geräte aus der Discovery-Liste ({@code /api/victron/ble/devices}) anhand
 * von Name-Pattern oder MAC. Konfiguriert über {@code victron.ble.blacklist-names} /
 * {@code victron.ble.blacklist-macs} (kommagetrennt). Jeder Name-Eintrag ist ein
 * case-insensitives Glob-Pattern auf den gesamten Namen ({@code *} = beliebig viele
 * Zeichen, {@code ?} = ein Zeichen); ein Eintrag ohne Wildcard matcht exakt.
 */
@ApplicationScoped
public class BleBlacklist {

    @Inject
    VictronConfig config;

    private List<Pattern> namePatterns;
    private List<String> macs;

    private List<Pattern> namePatterns() {
        if (namePatterns == null) {
            namePatterns = parseCsv(config.ble().blacklistNames()).stream()
                .map(BleBlacklist::globToPattern)
                .toList();
        }
        return namePatterns;
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
            for (Pattern pattern : namePatterns()) {
                if (pattern.matcher(name).matches()) return true;
            }
        }
        return false;
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                    regex.append(c);
                }
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
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
