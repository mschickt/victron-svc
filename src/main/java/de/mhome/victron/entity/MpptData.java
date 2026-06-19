package de.mhome.victron.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MpptData(
    String mac,
    String name,
    Instant timestamp,

    // Zustand
    int chargerState,           // 0=Off 2=Fault 3=Bulk 4=Absorption 5=Float
    String chargerStateLabel,
    int errorCode,

    // Batterie
    double batteryVoltageV,     // V
    double batteryCurrentA,     // A

    // Solar
    int panelPowerW,            // W

    // Ertrag
    int yieldTodayWh,           // Wh

    // Last (falls MPPT mit Lastausgang)
    Double loadCurrentA,        // A (null wenn N/A)
    Boolean loadState           // true = ein
) {
    public static String stateLabel(int state) {
        return switch (state) {
            case 0  -> "Off";
            case 2  -> "Fault";
            case 3  -> "Bulk";
            case 4  -> "Absorption";
            case 5  -> "Float";
            case 6  -> "Storage";
            case 7  -> "Equalize (manual)";
            case 11 -> "Power Supply";
            case 245-> "HUB-1";
            case 252-> "External Control";
            default -> "Unknown (" + state + ")";
        };
    }
}
