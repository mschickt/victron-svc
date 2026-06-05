package de.mhome.victron.control;

import de.mhome.victron.entity.SmartShuntData;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

@ApplicationScoped
public class SmartShuntDecoder {

    /**
     * Victron Battery Monitor / SmartShunt Record Type 0x02
     * Bit-Layout (entschlüsselt, LSB-first) — Quelle: keshavdv/victron-ble battery_monitor.py
     *
     *  [0  .. 15]  remaining_mins  uint16          → min  (0xFFFF = N/A)
     *  [16 .. 31]  voltage         int16  / 100    → V   (0x7FFF = N/A)
     *  [32 .. 47]  alarm_reason    uint16
     *  [48 .. 63]  aux             uint16           → Starter-V / Midpoint-V / Temp je nach Mode
     *  [64 .. 65]  aux_mode        uint2            → 0=Starter 1=Midpoint 2=Temp 3=Disabled
     *  [66 .. 87]  current         int22  / 1000   → A   (0x3FFFFF = N/A)
     *  [88 ..107]  consumed_ah     uint20 / 10 neg → Ah  (0xFFFFF = N/A)
     *  [108..117]  soc             uint10 / 10     → %   (0x3FF = N/A)
     */
    public SmartShuntData decode(String mac, String name, byte[] decrypted) {
        BitReader r = new BitReader(decrypted);

        int ttgRaw      = r.readUInt(0,  16);
        int voltageRaw  = r.readInt (16, 16);
        int alarmReason = r.readUInt(32, 16);
        int auxRaw      = r.readUInt(48, 16);
        int auxMode     = r.readUInt(64,  2);
        int currentRaw  = r.readInt (66, 22);
        int consumedRaw = r.readUInt(88, 20);
        int socRaw      = r.readUInt(108, 10);

        Double voltage  = (voltageRaw  == 0x7FFF)   ? null : voltageRaw  / 100.0;
        Double current  = (currentRaw  == 0x3FFFFF)  ? null : currentRaw  / 1000.0;
        Double soc      = (socRaw      == 0x3FF)     ? null : socRaw      / 10.0;
        Double consumed = (consumedRaw == 0xFFFFF)   ? null : -consumedRaw / 10.0;
        Integer ttg     = (ttgRaw      == 0xFFFF)    ? null : ttgRaw;

        // Aux-Eingang je nach Modus auflösen
        Double auxVoltage = null;
        Double tempC      = null;
        switch (auxMode) {
            case 0 -> auxVoltage = toSignedInt16(auxRaw) / 100.0;  // Starter-Spannung
            case 1 -> auxVoltage = auxRaw / 100.0;                  // Midpoint-Spannung
            case 2 -> tempC      = auxRaw / 100.0 - 273.15;         // Temperatur (K → °C)
            // 3 = Disabled → beide null
        }

        return new SmartShuntData(
            mac, name, Instant.now(),
            voltage, current, soc, consumed, ttg,
            alarmReason, alarmReason != 0,
            auxVoltage, tempC
        );
    }

    private static int toSignedInt16(int unsigned16) {
        return unsigned16 > 0x7FFF ? unsigned16 - 0x10000 : unsigned16;
    }
}
