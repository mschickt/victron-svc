package de.mhome.victron.control;

import de.mhome.victron.entity.SmartShuntData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmartShuntDecoderTest {

    private final SmartShuntDecoder decoder = new SmartShuntDecoder();

    /**
     * Baut ein Payload mit bekannten Werten zusammen und prüft das Decoding.
     * Hilfs-Setter schreibt LSB-first in das Bit-Array (analog zum Victron-Layout).
     */
    private static void writeBits(byte[] buf, int bitOffset, int bitLength, int value) {
        for (int i = 0; i < bitLength; i++) {
            int bit = (value >> i) & 1;
            int byteIdx = (bitOffset + i) / 8;
            int bitIdx  = (bitOffset + i) % 8;
            if (bit == 1) {
                buf[byteIdx] |= (1 << bitIdx);
            }
        }
    }

    @Test
    void decodesKnownValues() {
        byte[] buf = new byte[16];

        // battery_voltage int16 ×0.01 -> 12.80 V => 1280
        writeBits(buf, 0, 16, 1280);
        // alarm_reason
        writeBits(buf, 16, 16, 0);
        // aux_voltage (egal, da Temp gesetzt)
        writeBits(buf, 32, 16, 0);
        // battery_current int16 ×0.1 -> -2.5 A => -25 (0xFFE7 in 16 bit)
        writeBits(buf, 48, 16, -25 & 0xFFFF);
        // consumed_ah int20 ×0.1 -> -10.0 Ah => -100
        writeBits(buf, 64, 20, -100 & 0xFFFFF);
        // soc uint10 ×0.1 -> 87.5 % => 875
        writeBits(buf, 84, 10, 875);
        // time_to_go = N/A
        writeBits(buf, 94, 16, 0xFFFF);
        // alarm bit
        writeBits(buf, 110, 1, 0);
        // temperature = N/A (0x7F)
        writeBits(buf, 112, 7, 0x7F);

        SmartShuntData d = decoder.decode("AA:BB", "Shunt", buf);

        assertEquals(12.80, d.batteryVoltageV(), 1e-9);
        assertEquals(-2.5, d.batteryCurrentA(), 1e-9);
        assertEquals(-10.0, d.consumedAh(), 1e-9);
        assertEquals(87.5, d.stateOfChargePercent(), 1e-9);
        assertNull(d.timeToGoMinutes());
        assertFalse(d.alarm());
        assertNull(d.temperatureC());
    }
}
