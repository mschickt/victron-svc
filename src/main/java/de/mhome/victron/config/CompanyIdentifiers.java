package de.mhome.victron.config;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lookup für Bluetooth SIG "Company Identifiers" (Manufacturer-IDs aus BLE
 * Advertisements). Quelle: bluetooth-company-ids.csv (Classpath-Resource) —
 * die offizielle, aktuell gepflegte Bluetooth SIG Assigned Numbers-Liste
 * (https://www.bluetooth.com/specifications/assigned-numbers/, gepflegt unter
 * https://bitbucket.org/bluetooth-SIG/public/src/main/assigned_numbers/company_identifiers/company_identifiers.yaml),
 * ergänzt um ältere/inzwischen aus der offiziellen Liste entfernte IDs aus
 * https://gist.github.com/angorb/f92f76108b98bb0d81c74f60671e9c67, damit auch
 * Advertisements mit retired/reassigned Company-IDs aufgelöst werden.
 *
 * {@link #LOCAL_OVERRIDES} ergänzt das daneben, ohne die CSV zu verfälschen,
 * um IDs, die in den BLE-Advertisements dieses Setups beobachtet wurden, aber
 * keine echten Bluetooth SIG Company Identifier sind (z.B. der Bulltron/Daly-
 * BMS-Klon, der unter "Manufacturer ID" einfach die eigenen MAC-Bytes sendet).
 */
@ApplicationScoped
public class CompanyIdentifiers {

    private static final String RESOURCE = "/bluetooth-company-ids.csv";

    private static final Map<Integer, String> LOCAL_OVERRIDES = Map.of(
        0x7CC7, "Bulltron/Daly BMS"
    );

    private final Map<Integer, String> namesById = load();

    public String nameOf(int id) {
        String override = LOCAL_OVERRIDES.get(id);
        return override != null ? override : namesById.get(id);
    }

    private static Map<Integer, String> load() {
        Map<Integer, String> map = new HashMap<>();
        try (InputStream in = CompanyIdentifiers.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return map;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    int comma = line.indexOf(',');
                    if (comma < 0) continue;
                    String idHex = line.substring(0, comma).trim();
                    String name = unquote(line.substring(comma + 1).trim());
                    try {
                        map.put(Integer.decode(idHex), name);
                    } catch (NumberFormatException ignored) {
                        // skip malformed line
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + RESOURCE, e);
        }
        return map;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }
}
