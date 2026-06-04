package de.mhome.victron.control;

/**
 * Liest Werte aus einem Byte-Array im Victron-Bit-Layout: LSB-first
 * (least significant bit zuerst), Werte können über Byte-Grenzen reichen.
 */
public class BitReader {

    private final byte[] data;

    public BitReader(byte[] data) {
        this.data = data;
    }

    /** Liest 'bitLength' Bits ab Bit-Offset 'bitOffset', LSB-first (Victron Standard) */
    public int readUInt(int bitOffset, int bitLength) {
        int value = 0;
        for (int i = 0; i < bitLength; i++) {
            int byteIdx = (bitOffset + i) / 8;
            int bitIdx  = (bitOffset + i) % 8;
            if (byteIdx < data.length) {
                int bit = (data[byteIdx] >> bitIdx) & 1;
                value |= (bit << i);
            }
        }
        return value;
    }

    /** Vorzeichenbehafteter Wert (Sign-Extend) */
    public int readInt(int bitOffset, int bitLength) {
        int value = readUInt(bitOffset, bitLength);
        if (bitLength > 1 && (value & (1 << (bitLength - 1))) != 0) {
            value |= ~((1 << bitLength) - 1);
        }
        return value;
    }

    /** Convenience: gibt true wenn bit gesetzt */
    public boolean readBit(int bitOffset) {
        return readUInt(bitOffset, 1) == 1;
    }
}
