package de.mhome.victron.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Momentaufnahme einer Bulltron-Batterie (Daly Smart BMS), zusammengesetzt aus
 * mehreren Daly-Antwortframes (0x90/0x91/0x92/0x93/0x94).
 *
 * <p>Stromvorzeichen folgt der Victron-/SmartShunt-Konvention:
 * <b>negativ = Entladung, positiv = Ladung</b> (Daly liefert es bereits so —
 * gegen den SmartShunt am selben Akku verifiziert).
 *
 * <p>Felder, die in einem Pollzyklus nicht gelesen werden konnten, sind {@code null}
 * (per {@link JsonInclude} aus dem JSON ausgeblendet).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulltronData(
    String mac,
    String name,
    Instant timestamp,

    // ── 0x90: Pack-Summenwerte ───────────────────────────────────────────
    Double packVoltageV,          // V
    Double currentA,              // A  (negativ = Entladung, positiv = Ladung)
    Double stateOfChargePercent,  // %

    // ── 0x93: Restkapazität + MOSFET ─────────────────────────────────────
    Double remainingCapacityAh,   // Ah
    String mode,                  // "stationary" | "charging" | "discharging"
    Boolean chargeMosfetOn,
    Boolean dischargeMosfetOn,

    // ── 0x94: Topologie ──────────────────────────────────────────────────
    Integer cellCount,
    Integer tempSensorCount,
    Integer cycles,

    // ── 0x91: Zellspannungs-Extrema ──────────────────────────────────────
    Double minCellVoltageV,
    Integer minCellIndex,
    Double maxCellVoltageV,
    Integer maxCellIndex,

    // ── 0x92: Temperatur-Extrema (°C) ────────────────────────────────────
    Integer minTemperatureC,
    Integer maxTemperatureC
) {}
