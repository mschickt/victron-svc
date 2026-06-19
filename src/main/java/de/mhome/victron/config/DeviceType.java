package de.mhome.victron.config;

/** Unterstützte Gerätetypen. */
public enum DeviceType {
    MPPT,
    SMART_SHUNT,
    ORION_TR,
    /** Batterie mit Daly-kompatiblem Smart BMS (z. B. Bulltron) — siehe {@link Vendor#BULLTRON}. */
    BATTERY
}
