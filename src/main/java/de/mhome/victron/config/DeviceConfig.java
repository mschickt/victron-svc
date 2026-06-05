package de.mhome.victron.config;

/**
 * Ein konfiguriertes BLE-Gerät. Wird von {@link DeviceRegistry} aus den
 * Umgebungsvariablen {@code VICTRON_DEVICES_<n>_*} (siehe {@code .env}) erzeugt.
 *
 * <p>{@code vendor} bestimmt das Dekodierungsprotokoll. Default: {@link Vendor#VICTRON}.
 */
public record DeviceConfig(
    String mac,
    String name,
    Vendor vendor,
    DeviceType type,
    String advertisementKey
) {}
