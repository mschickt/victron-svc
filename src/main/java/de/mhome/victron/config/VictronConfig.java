package de.mhome.victron.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "victron")
public interface VictronConfig {

    Ble ble();

    interface Ble {
        String adapter();

        @WithName("scan-interval")
        String scanInterval();

        /** Intervall des periodischen BLE-Inventory-Scans (Log + Metrik aller sichtbaren Geräte). */
        @WithName("inventory-interval")
        @WithDefault("5m")
        String inventoryInterval();

        /** When false (default) scanning stays off at boot and is started via the REST API. */
        @WithName("auto-start")
        @WithDefault("false")
        boolean autoStart();

        /** Kommagetrennte Namens-Präfixe (case-insensitive); Treffer werden aus der Discovery-Liste ausgeblendet. */
        @WithName("blacklist-names")
        @WithDefault("")
        String blacklistNames();

        /** Kommagetrennte MAC-Adressen; Treffer werden aus der Discovery-Liste ausgeblendet. */
        @WithName("blacklist-macs")
        @WithDefault("")
        String blacklistMacs();

        /**
         * Kommagetrennte {@code MAC=Anzeigename}-Paare für nicht konfigurierte, aber
         * bekannte Fremdgeräte (z. B. AirTags) — überschreibt nur den Anzeigenamen in
         * der Inventory-Liste, ohne das Gerät zu pollen oder zu blacklisten.
         */
        @WithName("known-names")
        @WithDefault("")
        String knownNames();
    }
}
