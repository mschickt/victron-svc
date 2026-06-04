package de.mhome.victron.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmartShuntData(
    String mac,
    String name,
    Instant timestamp,

    double batteryVoltageV,     // V
    double batteryCurrentA,     // A (negativ = Entladung)
    double stateOfChargePercent,// %
    double consumedAh,          // Ah (negativ)
    Integer timeToGoMinutes,    // null wenn laden oder kein Signal
    int alarmReason,
    boolean alarm,

    // Hilfseingang (falls konfiguriert: Aux-Spannung oder Midpoint oder Temp)
    Double auxVoltageV,
    Double temperatureC         // °C (null wenn nicht konfiguriert)
) {}
