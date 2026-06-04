package de.mhome.victron.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrionData(
    String mac,
    String name,
    Instant timestamp,

    int state,                  // 0=Off 9=Blocked/Muted 11=Low Power
    String stateLabel,
    int errorCode,

    double inputVoltageV,       // V (Starterbatterie)
    double outputVoltageV,      // V (Servicebatterie)

    // Je nach Firmware
    Double inputCurrentA,
    Double outputCurrentA,

    int offReason               // Bitmask
) {
    public static String stateLabel(int state) {
        return switch (state) {
            case 0  -> "Off";
            case 9  -> "Blocked / Muted";
            case 11 -> "Low Power (ECO)";
            default -> "On / Converting";
        };
    }
}
