package de.mhome.victron.control;

import java.util.Arrays;
import java.util.Optional;

public enum RecordType {
    SOLAR_CHARGER(0x01),
    BATTERY_MONITOR(0x02),
    DC_DC_CONVERTER(0x04),
    INVERTER(0x05),
    UNKNOWN(-1);

    private final int id;

    RecordType(int id) { this.id = id; }

    public int getId() { return id; }

    public static Optional<RecordType> fromId(int id) {
        return Arrays.stream(values())
            .filter(r -> r.id == id)
            .findFirst();
    }
}
