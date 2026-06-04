package de.mhome.victron.control;

import de.mhome.victron.entity.OrionData;
import de.mhome.victron.control.BitReader;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

@ApplicationScoped
public class OrionDecoder {

    /**
     * Victron DC/DC Converter Record Type 0x04 (Orion-Tr Smart)
     * Bit-Layout (entschlüsselt, LSB-first):
     *
     *  [0 ..  7]  state            uint8
     *  [8 .. 15]  error            uint8
     *  [16.. 27]  input_voltage    uint12 × 0.01 → V  (0xFFF = N/A)
     *  [28.. 39]  output_voltage   uint12 × 0.01 → V  (0xFFF = N/A)
     *  [40.. 57]  off_reason       uint18  (Bitmask)
     *  [58.. 67]  input_current    uint10 × 0.1  → A  (0x3FF = N/A)  ← firmware-abhängig
     *  [68.. 77]  output_current   uint10 × 0.1  → A  (0x3FF = N/A)
     */
    public OrionData decode(String mac, String name, byte[] decrypted) {
        BitReader r = new BitReader(decrypted);

        int state = r.readUInt(0, 8);
        int error = r.readUInt(8, 8);

        int inVRaw  = r.readUInt(16, 12);
        int outVRaw = r.readUInt(28, 12);
        double inputVoltage  = (inVRaw  == 0xFFF) ? 0.0 : inVRaw  * 0.01;
        double outputVoltage = (outVRaw == 0xFFF) ? 0.0 : outVRaw * 0.01;

        int offReason = r.readUInt(40, 18);

        int inARaw  = r.readUInt(58, 10);
        int outARaw = r.readUInt(68, 10);
        Double inputCurrent  = (inARaw  == 0x3FF) ? null : inARaw  * 0.1;
        Double outputCurrent = (outARaw == 0x3FF) ? null : outARaw * 0.1;

        return new OrionData(
            mac, name, Instant.now(),
            state, OrionData.stateLabel(state), error,
            inputVoltage, outputVoltage,
            inputCurrent, outputCurrent,
            offReason
        );
    }
}
