package de.mhome.victron.config;

/**
 * BLE-Protokoll / Hersteller eines konfigurierten Geräts.
 * Wird von VictronBleScanner genutzt, um den passenden Decoder zu wählen.
 */
public enum Vendor {
    /** Victron Energy — AES-CTR Instant Readout (0x02E1) */
    VICTRON
}
