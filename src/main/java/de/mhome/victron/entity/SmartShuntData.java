package de.mhome.victron.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SmartShuntData(
    String mac,
    String name,
    Instant timestamp,

    Double batteryVoltageV,      // V  (null wenn N/A)
    Double batteryCurrentA,      // A  negativ = Entladung (null wenn N/A)
    Double stateOfChargePercent, // %  (null wenn N/A)
    Double consumedAh,           // Ah negativ (null wenn N/A)
    Integer timeToGoMinutes,     // min (null wenn N/A oder unbekannt)
    int alarmReason,             // Bitfeld (0 = kein Alarm)
    boolean alarm,

    // Hilfseingang — nur eines der drei ist != null, je nach Konfiguration
    Double auxVoltageV,          // Starter- oder Midpoint-Spannung
    Double temperatureC          // °C wenn Temp-Sensor konfiguriert
) {}
