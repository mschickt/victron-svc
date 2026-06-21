package de.mhome.victron.control;

import de.mhome.victron.entity.BleScanReading;
import de.mhome.victron.entity.BulltronData;
import de.mhome.victron.entity.MpptData;
import de.mhome.victron.entity.OrionData;
import de.mhome.victron.entity.SmartShuntData;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

@ApplicationScoped
public class ReadingRepository {

    private static final Logger LOG = Logger.getLogger(ReadingRepository.class);

    @Inject DataSource ds;

    void onStart(@Observes StartupEvent ev) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS mppt_reading (
                  ts             INTEGER NOT NULL,
                  mac            TEXT    NOT NULL,
                  name           TEXT,
                  charger_state  INTEGER,
                  error_code     INTEGER,
                  battery_v      REAL,
                  battery_a      REAL,
                  panel_v        REAL,
                  panel_w        INTEGER,
                  yield_today_wh INTEGER,
                  load_a         REAL,
                  load_on        INTEGER
                )
            """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mppt_mac_ts ON mppt_reading(mac, ts)");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS shunt_reading (
                  ts            INTEGER NOT NULL,
                  mac           TEXT    NOT NULL,
                  name          TEXT,
                  battery_v     REAL,
                  battery_a     REAL,
                  soc_pct       REAL,
                  consumed_ah   REAL,
                  ttg_minutes   INTEGER,
                  alarm_reason  INTEGER,
                  alarm         INTEGER,
                  aux_v         REAL,
                  temp_c        REAL
                )
            """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_shunt_mac_ts ON shunt_reading(mac, ts)");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS orion_reading (
                  ts          INTEGER NOT NULL,
                  mac         TEXT    NOT NULL,
                  name        TEXT,
                  state       INTEGER,
                  error_code  INTEGER,
                  input_v     REAL,
                  output_v    REAL,
                  input_a     REAL,
                  output_a    REAL,
                  off_reason  INTEGER
                )
            """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_orion_mac_ts ON orion_reading(mac, ts)");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS battery_reading (
                  ts             INTEGER NOT NULL,
                  mac            TEXT    NOT NULL,
                  name           TEXT,
                  pack_v         REAL,
                  current_a      REAL,
                  soc_pct        REAL,
                  remaining_ah   REAL,
                  mode           TEXT,
                  charge_mos     INTEGER,
                  discharge_mos  INTEGER,
                  cell_count     INTEGER,
                  temp_count     INTEGER,
                  cycles         INTEGER,
                  min_cell_v     REAL,
                  max_cell_v     REAL,
                  min_temp_c     INTEGER,
                  max_temp_c     INTEGER
                )
            """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_battery_mac_ts ON battery_reading(mac, ts)");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ble_scan_reading (
                  ts                INTEGER NOT NULL,
                  mac               TEXT    NOT NULL,
                  name              TEXT,
                  rssi_dbm          INTEGER,
                  manufacturer_ids  TEXT,
                  configured        INTEGER NOT NULL
                )
            """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ble_scan_mac_ts ON ble_scan_reading(mac, ts)");

            LOG.info("SQLite schema initialised");
        } catch (SQLException e) {
            LOG.error("Failed to initialise SQLite schema", e);
        }
    }

    public void insert(MpptData d) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO mppt_reading(ts, mac, name, charger_state, error_code, " +
                 "battery_v, battery_a, panel_w, yield_today_wh, load_a, load_on) " +
                 "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, d.timestamp().toEpochMilli());
            ps.setString(2, d.mac());
            ps.setString(3, d.name());
            ps.setInt(4, d.chargerState());
            ps.setInt(5, d.errorCode());
            ps.setDouble(6, d.batteryVoltageV());
            ps.setDouble(7, d.batteryCurrentA());
            ps.setInt(8, d.panelPowerW());
            ps.setInt(9, d.yieldTodayWh());
            if (d.loadCurrentA() == null) ps.setNull(10, java.sql.Types.REAL);
            else                          ps.setDouble(10, d.loadCurrentA());
            ps.setInt(11, Boolean.TRUE.equals(d.loadState()) ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("MPPT insert failed for %s: %s", d.mac(), e.getMessage());
        }
    }

    public void insert(SmartShuntData d) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO shunt_reading(ts, mac, name, battery_v, battery_a, soc_pct, " +
                 "consumed_ah, ttg_minutes, alarm_reason, alarm, aux_v, temp_c) " +
                 "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, d.timestamp().toEpochMilli());
            ps.setString(2, d.mac());
            ps.setString(3, d.name());
            ps.setDouble(4, d.batteryVoltageV());
            ps.setDouble(5, d.batteryCurrentA());
            ps.setDouble(6, d.stateOfChargePercent());
            ps.setDouble(7, d.consumedAh());
            if (d.timeToGoMinutes() == null) ps.setNull(8, java.sql.Types.INTEGER);
            else                             ps.setInt(8, d.timeToGoMinutes());
            ps.setInt(9, d.alarmReason());
            ps.setInt(10, d.alarm() ? 1 : 0);
            if (d.auxVoltageV() == null) ps.setNull(11, java.sql.Types.REAL);
            else                         ps.setDouble(11, d.auxVoltageV());
            if (d.temperatureC() == null) ps.setNull(12, java.sql.Types.REAL);
            else                          ps.setDouble(12, d.temperatureC());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("Shunt insert failed for %s: %s", d.mac(), e.getMessage());
        }
    }

    public void insert(OrionData d) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO orion_reading(ts, mac, name, state, error_code, " +
                 "input_v, output_v, input_a, output_a, off_reason) " +
                 "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, d.timestamp().toEpochMilli());
            ps.setString(2, d.mac());
            ps.setString(3, d.name());
            ps.setInt(4, d.state());
            ps.setInt(5, d.errorCode());
            ps.setDouble(6, d.inputVoltageV());
            ps.setDouble(7, d.outputVoltageV());
            if (d.inputCurrentA() == null) ps.setNull(8, java.sql.Types.REAL);
            else                           ps.setDouble(8, d.inputCurrentA());
            if (d.outputCurrentA() == null) ps.setNull(9, java.sql.Types.REAL);
            else                            ps.setDouble(9, d.outputCurrentA());
            ps.setInt(10, d.offReason());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("Orion insert failed for %s: %s", d.mac(), e.getMessage());
        }
    }

    public void insert(BulltronData d) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO battery_reading(ts, mac, name, pack_v, current_a, soc_pct, remaining_ah, " +
                 "mode, charge_mos, discharge_mos, cell_count, temp_count, cycles, " +
                 "min_cell_v, max_cell_v, min_temp_c, max_temp_c) " +
                 "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, d.timestamp().toEpochMilli());
            ps.setString(2, d.mac());
            ps.setString(3, d.name());
            setNullableDouble(ps, 4, d.packVoltageV());
            setNullableDouble(ps, 5, d.currentA());
            setNullableDouble(ps, 6, d.stateOfChargePercent());
            setNullableDouble(ps, 7, d.remainingCapacityAh());
            if (d.mode() == null) ps.setNull(8, java.sql.Types.VARCHAR);
            else                  ps.setString(8, d.mode());
            setNullableBool(ps, 9, d.chargeMosfetOn());
            setNullableBool(ps, 10, d.dischargeMosfetOn());
            setNullableInt(ps, 11, d.cellCount());
            setNullableInt(ps, 12, d.tempSensorCount());
            setNullableInt(ps, 13, d.cycles());
            setNullableDouble(ps, 14, d.minCellVoltageV());
            setNullableDouble(ps, 15, d.maxCellVoltageV());
            setNullableInt(ps, 16, d.minTemperatureC());
            setNullableInt(ps, 17, d.maxTemperatureC());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("Battery insert failed for %s: %s", d.mac(), e.getMessage());
        }
    }

    public void insert(BleScanReading d) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO ble_scan_reading(ts, mac, name, rssi_dbm, manufacturer_ids, configured) " +
                 "VALUES (?,?,?,?,?,?)")) {
            ps.setLong(1, d.timestamp().toEpochMilli());
            ps.setString(2, d.mac());
            ps.setString(3, d.name());
            setNullableInt(ps, 4, d.rssiDbm());
            ps.setString(5, d.manufacturerIds() == null ? null : String.join(",", d.manufacturerIds()));
            ps.setInt(6, d.configured() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf("BLE-Scan insert failed for %s: %s", d.mac(), e.getMessage());
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.REAL);
        else           ps.setDouble(idx, v);
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER);
        else           ps.setInt(idx, v);
    }

    private static void setNullableBool(PreparedStatement ps, int idx, Boolean v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER);
        else           ps.setInt(idx, v ? 1 : 0);
    }
}
