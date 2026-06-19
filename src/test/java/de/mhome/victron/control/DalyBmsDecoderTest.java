package de.mhome.victron.control;

import de.mhome.victron.entity.BulltronData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DalyBmsDecoderTest {

    private final DalyBmsDecoder decoder = new DalyBmsDecoder();

    /** Baut einen kompletten 13-Byte-Antwortframe mit korrekter CRC für ein Command. */
    private static byte[] frame(int command, int... data) {
        assertEquals(8, data.length, "Daly-Datenblock muss 8 Byte sein");
        byte[] f = new byte[13];
        f[0] = (byte) 0xA5;
        f[1] = (byte) 0x80;
        f[2] = (byte) command;
        f[3] = (byte) 0x08;
        for (int i = 0; i < 8; i++) f[4 + i] = (byte) data[i];
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += f[i] & 0xFF;
        f[12] = (byte) (sum & 0xFF);
        return f;
    }

    @Test
    void requestFrameHasCorrectStructureAndCrc() {
        byte[] req = decoder.request(DalyBmsDecoder.CMD_SOC);
        assertEquals(13, req.length);
        assertEquals((byte) 0xA5, req[0]);
        assertEquals((byte) 0x80, req[1]);
        assertEquals((byte) 0x90, req[2]);
        assertEquals((byte) 0x08, req[3]);
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += req[i] & 0xFF;
        assertEquals((byte) (sum & 0xFF), req[12]);
    }

    @Test
    void payloadRejectsBadCrcWrongCommandAndLength() {
        byte[] good = frame(0x90, 0, 132, 0, 0, 0x75, 0x62, 0x03, 0xB6);
        assertNotNull(decoder.payload(good, 0x90));

        // Falsches erwartetes Command
        assertNull(decoder.payload(good, 0x91));

        // CRC kaputt
        byte[] badCrc = good.clone();
        badCrc[12] ^= 0xFF;
        assertNull(decoder.payload(badCrc, 0x90));

        // Falsche Länge
        assertNull(decoder.payload(new byte[12], 0x90));
    }

    @Test
    void decodesAllCommands() {
        Map<Integer, byte[]> data = new HashMap<>();
        // 0x90: pack 13.2V, current raw 30050 -> +5.0A (Ladung, Victron-Konvention), SoC 95.0%
        data.put(0x90, decoder.payload(frame(0x90, 0x00, 0x84, 0, 0, 0x75, 0x62, 0x03, 0xB6), 0x90));
        // 0x91: max 3.456V @cell2, min 3.301V @cell7
        data.put(0x91, decoder.payload(frame(0x91, 0x0D, 0x80, 2, 0x0C, 0xE5, 7, 0, 0), 0x91));
        // 0x92: max 25°C (raw 65), min 18°C (raw 58)
        data.put(0x92, decoder.payload(frame(0x92, 65, 1, 58, 2, 0, 0, 0, 0), 0x92));
        // 0x93: mode discharging, beide MOSFETs an, 100.000 Ah-> mAh 100000
        data.put(0x93, decoder.payload(frame(0x93, 2, 1, 1, 0x10, 0x00, 0x01, 0x86, 0xA0), 0x93));
        // 0x94: 16 Zellen, 2 Sensoren, 12 Zyklen
        data.put(0x94, decoder.payload(frame(0x94, 16, 2, 0, 0, 0, 0x00, 0x0C, 0), 0x94));

        BulltronData d = decoder.decode("C7:7C:03:02:11:5D", "Bulltron", data, Instant.EPOCH);

        assertEquals(13.2, d.packVoltageV(), 1e-9);
        assertEquals(5.0, d.currentA(), 1e-9);           // positiv = Ladung (Victron-Konvention)
        assertEquals(95.0, d.stateOfChargePercent(), 1e-9);

        assertEquals(3.456, d.maxCellVoltageV(), 1e-9);
        assertEquals(2, d.maxCellIndex());
        assertEquals(3.301, d.minCellVoltageV(), 1e-9);
        assertEquals(7, d.minCellIndex());

        assertEquals(25, d.maxTemperatureC());
        assertEquals(18, d.minTemperatureC());

        assertEquals("discharging", d.mode());
        assertTrue(d.chargeMosfetOn());
        assertTrue(d.dischargeMosfetOn());
        assertEquals(100.0, d.remainingCapacityAh(), 1e-9);

        assertEquals(16, d.cellCount());
        assertEquals(2, d.tempSensorCount());
        assertEquals(12, d.cycles());
    }

    @Test
    void missingCommandsLeaveFieldsNull() {
        Map<Integer, byte[]> data = new HashMap<>();
        data.put(0x90, decoder.payload(frame(0x90, 0x00, 0x84, 0, 0, 0x75, 0x30, 0x03, 0xB6), 0x90));

        BulltronData d = decoder.decode("mac", "n", data, Instant.EPOCH);
        assertNotNull(d.packVoltageV());
        assertNull(d.maxCellVoltageV());
        assertNull(d.mode());
        assertNull(d.cellCount());
    }
}
