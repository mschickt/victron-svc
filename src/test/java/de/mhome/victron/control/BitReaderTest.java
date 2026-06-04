package de.mhome.victron.control;

import de.mhome.victron.control.BitReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitReaderTest {

    @Test
    void readsLsbFirstWithinByte() {
        // 0b00000101 = bit0=1, bit1=0, bit2=1
        BitReader r = new BitReader(new byte[]{0x05});
        assertEquals(1, r.readUInt(0, 1));
        assertEquals(0, r.readUInt(1, 1));
        assertEquals(1, r.readUInt(2, 1));
        assertEquals(5, r.readUInt(0, 8));
    }

    @Test
    void readsAcrossByteBoundary() {
        // little-endian uint16 = 0x0102 -> bytes [0x02, 0x01]
        BitReader r = new BitReader(new byte[]{0x02, 0x01});
        assertEquals(0x0102, r.readUInt(0, 16));
    }

    @Test
    void signExtendsNegativeValue() {
        // int16 = -1 -> 0xFFFF -> bytes [0xFF, 0xFF]
        BitReader r = new BitReader(new byte[]{(byte) 0xFF, (byte) 0xFF});
        assertEquals(-1, r.readInt(0, 16));
    }

    @Test
    void signExtendsPositiveValue() {
        // int16 = 1234 -> bytes little-endian [0xD2, 0x04]
        BitReader r = new BitReader(new byte[]{(byte) 0xD2, 0x04});
        assertEquals(1234, r.readInt(0, 16));
    }

    @Test
    void readBitConvenience() {
        BitReader r = new BitReader(new byte[]{(byte) 0b0000_0010});
        assertFalse(r.readBit(0));
        assertTrue(r.readBit(1));
    }
}
