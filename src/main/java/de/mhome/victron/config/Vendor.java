package de.mhome.victron.config;

/**
 * BLE-Protokoll / Hersteller eines konfigurierten Geräts.
 * Wird von VictronBleScanner genutzt, um den passenden Decoder zu wählen.
 */
public enum Vendor {
    /** Victron Energy — passives AES-CTR Instant Readout Advertisement (0x02E1) */
    VICTRON,
    /**
     * Bulltron LiFePO4 (rebadgeter Daly Smart BMS, BLE-Name {@code DL-…}).
     * Sendet KEINE Daten im Advertisement — erfordert eine aktive GATT-Verbindung,
     * Polling über {@code de.mhome.victron.boundary.DalyBleScanner}.
     */
    BULLTRON
}
