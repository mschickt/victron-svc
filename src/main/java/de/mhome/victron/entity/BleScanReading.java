package de.mhome.victron.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Ein per periodischem BLE-Inventory-Scan gesehenes Gerät (siehe BleInventory), zur Persistenz. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BleScanReading(
    String mac,
    String name,
    Instant timestamp,
    Integer rssiDbm,
    List<String> manufacturerIds,
    boolean configured
) {}
