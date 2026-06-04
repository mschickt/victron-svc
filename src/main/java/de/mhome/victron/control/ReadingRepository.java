package de.mhome.victron.control;

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

            LOG.info("SQLite schema initialised");
        } catch (SQLException e) {
            LOG.error("Failed to initialise SQLite schema", e);
        }
    }

    public void insert(MpptData d) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO mppt_reading(ts, mac, name, charger_state, error_code, " +
                 "battery_v, battery_a, panel_v, panel_w, yield_today_wh, load_a, load_on) " +
                 "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, d.timestamp().toEpochMilli());
            ps.setString(2, d.mac());
            ps.setString(3, d.name());
            ps.setInt(4, d.chargerState());
            ps.setInt(5, d.errorCode());
            ps.setDouble(6, d.batteryVoltageV());
            ps.setDouble(7, d.batteryCurrentA());
            ps.setDouble(8, d.panelVoltageV());
            ps.setInt(9, d.panelPowerW());
            ps.setInt(10, d.yieldTodayWh());
            if (d.loadCurrentA() == null) ps.setNull(11, java.sql.Types.REAL);
            else                          ps.setDouble(11, d.loadCurrentA());
            ps.setInt(12, Boolean.TRUE.equals(d.loadState()) ? 1 : 0);
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
}
