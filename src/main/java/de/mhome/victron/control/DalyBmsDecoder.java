package de.mhome.victron.control;

import de.mhome.victron.entity.BulltronData;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;

/**
 * Dekoder für das klassische Daly Smart BMS BLE-Protokoll (Bulltron & Co.).
 *
 * <p>Im Gegensatz zu Victron sendet das Daly BMS nichts im Advertisement: Über eine
 * GATT-Verbindung wird ein 13-Byte-Requestframe geschrieben und die Antwort als
 * 13-Byte-Notification empfangen. Dieser Dekoder ist rein (keine BLE-Abhängigkeit) und
 * baut die Requestframes bzw. zerlegt die Antwortframes — die GATT-Mechanik liegt in
 * {@code de.mhome.victron.boundary.DalyBleScanner}.
 *
 * <p>Frameaufbau (jeweils 13 Byte):
 * <pre>
 *   [0]=0xA5  [1]=Host(0x80=BLE)  [2]=Command  [3]=Len(0x08)  [4..11]=8 Datenbytes  [12]=CRC
 *   CRC = (Summe der Bytes [0..11]) &amp; 0xFF
 * </pre>
 *
 * Offsets/Skalierung gegen die Referenz dreadnought/python-daly-bms verifiziert.
 */
@ApplicationScoped
public class DalyBmsDecoder {

    // GATT-Layout der Daly-Firmware im Bulltron ("DL-"-Name), per GATT-Dump verifiziert:
    // Service 0xFFF0, fff1 = Notify (RX/Antworten), fff2 = Write (TX/Requests).
    // (Hinweis: andere Daly-Firmware nutzt 0xFF00/ff01/ff02 — dieses Gerät tut das NICHT.)
    public static final String SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String NOTIFY_UUID  = "0000fff1-0000-1000-8000-00805f9b34fb";
    public static final String WRITE_UUID   = "0000fff2-0000-1000-8000-00805f9b34fb";

    public static final int CMD_SOC        = 0x90; // Pack-Spannung, Strom, SoC
    public static final int CMD_CELL_RANGE = 0x91; // min/max Zellspannung
    public static final int CMD_TEMP_RANGE = 0x92; // min/max Temperatur
    public static final int CMD_MOSFET     = 0x93; // Modus, MOSFETs, Restkapazität
    public static final int CMD_STATUS     = 0x94; // Zellzahl, Sensorzahl, Zyklen

    /** Reihenfolge, in der pro Pollzyklus abgefragt wird. */
    public static final int[] POLL_COMMANDS = { CMD_SOC, CMD_CELL_RANGE, CMD_TEMP_RANGE, CMD_MOSFET, CMD_STATUS };

    private static final int FRAME_PREFIX = 0xA5;
    private static final int HOST_BLE     = 0x80;
    private static final int DATA_LEN     = 0x08;
    public  static final int FRAME_LEN    = 13;

    /** Baut den 13-Byte-Requestframe für ein Command (0x90…0x98). */
    public byte[] request(int command) {
        byte[] f = new byte[FRAME_LEN];
        f[0] = (byte) FRAME_PREFIX;
        f[1] = (byte) HOST_BLE;
        f[2] = (byte) command;
        f[3] = (byte) DATA_LEN;
        // f[4..11] bleiben 0
        f[12] = checksum(f, 0, 12);
        return f;
    }

    /**
     * Validiert einen 13-Byte-Antwortframe und liefert die 8 Datenbytes (Index 4..11)
     * zurück, oder {@code null} bei falscher Länge/Präfix/Command oder CRC-Fehler.
     *
     * @param expectedCommand erwartetes Command-Byte (zur Zuordnung der Antwort)
     */
    public byte[] payload(byte[] frame, int expectedCommand) {
        if (frame == null || frame.length != FRAME_LEN) return null;
        if ((frame[0] & 0xFF) != FRAME_PREFIX) return null;
        if ((frame[2] & 0xFF) != (expectedCommand & 0xFF)) return null;
        if (checksum(frame, 0, 12) != frame[12]) return null;
        byte[] data = new byte[8];
        System.arraycopy(frame, 4, data, 0, 8);
        return data;
    }

    /**
     * Setzt aus den (bereits per {@link #payload} extrahierten) 8-Byte-Datenblöcken
     * je Command eine {@link BulltronData} zusammen. Fehlende Commands → entsprechende
     * Felder bleiben {@code null}.
     *
     * @param dataByCommand Command-Byte → 8 Datenbytes
     */
    public BulltronData decode(String mac, String name, Map<Integer, byte[]> dataByCommand, Instant timestamp) {
        Double  packV = null, current = null, soc = null, capAh = null;
        Double  minCellV = null, maxCellV = null;
        Integer minCellIdx = null, maxCellIdx = null, minTemp = null, maxTemp = null;
        Integer cells = null, temps = null, cycles = null;
        String  mode = null;
        Boolean chgMos = null, disMos = null;

        byte[] d;

        if ((d = dataByCommand.get(CMD_SOC)) != null) {
            packV   = s16(d, 0) / 10.0;
            // Daly-Strom: Rohwert mit Offset 30000, Einheit 0,1 A. Vorzeichen entspricht
            // bereits der Victron-Konvention (positiv = Ladung, negativ = Entladung) — gegen
            // den SmartShunt am selben Akku verifiziert (beide +1,3 A beim Laden) und mit dem
            // Daly-Modusbyte 0x93 (charging) konsistent. KEINE Vorzeichendrehung.
            current = (s16(d, 4) - 30000) / 10.0;
            soc     = s16(d, 6) / 10.0;
        }
        if ((d = dataByCommand.get(CMD_CELL_RANGE)) != null) {
            maxCellV   = u16(d, 0) / 1000.0;
            maxCellIdx = u8(d, 2);
            minCellV   = u16(d, 3) / 1000.0;
            minCellIdx = u8(d, 5);
        }
        if ((d = dataByCommand.get(CMD_TEMP_RANGE)) != null) {
            maxTemp = u8(d, 0) - 40;
            minTemp = u8(d, 2) - 40;
        }
        if ((d = dataByCommand.get(CMD_MOSFET)) != null) {
            mode   = switch (u8(d, 0)) { case 0 -> "stationary"; case 1 -> "charging"; default -> "discharging"; };
            chgMos = u8(d, 1) != 0;
            disMos = u8(d, 2) != 0;
            capAh  = s32(d, 4) / 1000.0;
        }
        if ((d = dataByCommand.get(CMD_STATUS)) != null) {
            cells  = u8(d, 0);
            temps  = u8(d, 1);
            cycles = u16(d, 5);
        }

        return new BulltronData(mac, name, timestamp,
            packV, current, soc,
            capAh, mode, chgMos, disMos,
            cells, temps, cycles,
            minCellV, minCellIdx, maxCellV, maxCellIdx,
            minTemp, maxTemp);
    }

    private static byte checksum(byte[] b, int from, int len) {
        int sum = 0;
        for (int i = from; i < from + len; i++) sum += b[i] & 0xFF;
        return (byte) (sum & 0xFF);
    }

    private static int u8(byte[] b, int i)  { return b[i] & 0xFF; }
    private static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static int s16(byte[] b, int i) { return (short) u16(b, i); }
    private static int s32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }
}
