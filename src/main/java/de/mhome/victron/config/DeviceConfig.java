package de.mhome.victron.config;

/**
 * Ein konfiguriertes Victron-Gerät. Wird von {@link DeviceRegistry} aus den
 * Umgebungsvariablen {@code VICTRON_DEVICES_<n>_*} (siehe {@code .env}) erzeugt.
 */
public record DeviceConfig(String mac, String name, DeviceType type, String advertisementKey) {
}
