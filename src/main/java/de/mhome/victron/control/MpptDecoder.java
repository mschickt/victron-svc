package de.mhome.victron.control;

import de.mhome.victron.entity.MpptData;
import de.mhome.victron.control.BitReader;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

@ApplicationScoped
public class MpptDecoder {

    /**
     * Victron Solar Charger Record Type 0x01
     * Bit-Layout (entschlüsselt, LSB-first):
     *
     *  [0 .. 7]   charger_state    uint8
     *  [8 .. 15]  error_code       uint8
     *  [16.. 31]  battery_voltage  int16  × 0.01  → V
     *  [32.. 47]  battery_current  int16  × 0.1   → A
     *  [48.. 63]  yield_today      uint16 × 10    → Wh
     *  [64.. 79]  panel_power      uint16          → W
     *  [80.. 88]  load_current     uint9  × 0.1   → A  (0x1FF = N/A)
     *  [89]       load_state       bit
     *  [90..103]  panel_voltage    uint14 × 0.01  → V  (0x3FFF = N/A)
     */
    public MpptData decode(String mac, String name, byte[] decrypted) {
        BitReader r = new BitReader(decrypted);

        int chargerState = r.readUInt(0, 8);
        int errorCode    = r.readUInt(8, 8);

        double batteryVoltage = r.readInt(16, 16) * 0.01;
        double batteryCurrent = r.readInt(32, 16) * 0.1;

        int yieldToday = r.readUInt(48, 16) * 10;
        int panelPower = r.readUInt(64, 16);

        int loadCurrentRaw = r.readUInt(80, 9);
        Double loadCurrent = (loadCurrentRaw == 0x1FF) ? null : loadCurrentRaw * 0.1;
        boolean loadState  = r.readBit(89);

        int panelVoltageRaw = r.readUInt(90, 14);
        double panelVoltage = (panelVoltageRaw == 0x3FFF) ? 0.0 : panelVoltageRaw * 0.01;

        return new MpptData(
            mac, name, Instant.now(),
            chargerState, MpptData.stateLabel(chargerState), errorCode,
            batteryVoltage, batteryCurrent,
            panelVoltage, panelPower,
            yieldToday,
            loadCurrent, loadState
        );
    }
}
