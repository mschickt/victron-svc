package de.mhome.victron.control;

import de.mhome.victron.entity.SmartShuntData;
import de.mhome.victron.control.BitReader;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

@ApplicationScoped
public class SmartShuntDecoder {

    /**
     * Victron Battery Monitor Record Type 0x02
     * Bit-Layout (entschlüsselt, LSB-first):
     *
     *  [0 .. 15]  battery_voltage  int16  × 0.01  → V
     *  [16.. 31]  alarm_reason     uint16
     *  [32.. 47]  aux_voltage      int16  × 0.01  → V  (oder Temp/Midpoint je Konfig)
     *  [48.. 63]  battery_current  int16  × 0.1   → A
     *  [64.. 83]  consumed_ah      int20  × 0.1   → Ah
     *  [84.. 93]  state_of_charge  uint10 × 0.1   → %
     *  [94..109]  time_to_go       uint16          → Minuten (0xFFFF = N/A)
     *  [110]      alarm            bit
     *  [111]      (reserviert)
     *  [112..118] temperature      uint7           → K - 273 = °C (0x7F = N/A)
     */
    public SmartShuntData decode(String mac, String name, byte[] decrypted) {
        BitReader r = new BitReader(decrypted);

        double batteryVoltage = r.readInt(0, 16) * 0.01;
        int alarmReason       = r.readUInt(16, 16);
        int auxRaw            = r.readInt(32, 16);

        double batteryCurrent = r.readInt(48, 16) * 0.1;
        double consumedAh     = r.readInt(64, 20) * 0.1;
        double soc            = r.readUInt(84, 10) * 0.1;

        int ttgRaw    = r.readUInt(94, 16);
        Integer ttg   = (ttgRaw == 0xFFFF) ? null : ttgRaw;

        boolean alarm = r.readBit(110);

        int tempRaw = r.readUInt(112, 7);
        Double tempC = (tempRaw == 0x7F) ? null : (tempRaw - 273.0);

        // aux-Eingang: wenn Temperatur konfiguriert → tempC, sonst Spannung
        Double auxVoltage = (tempRaw != 0x7F) ? null : auxRaw * 0.01;

        return new SmartShuntData(
            mac, name, Instant.now(),
            batteryVoltage, batteryCurrent, soc, consumedAh, ttg,
            alarmReason, alarm,
            auxVoltage, tempC
        );
    }
}
