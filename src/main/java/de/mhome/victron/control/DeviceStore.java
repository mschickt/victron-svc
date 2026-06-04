package de.mhome.victron.control;

import de.mhome.victron.entity.MpptData;
import de.mhome.victron.entity.OrionData;
import de.mhome.victron.entity.SmartShuntData;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

@ApplicationScoped
public class DeviceStore {

    private final Map<String, MpptData>       mpptMap  = new ConcurrentHashMap<>();
    private final Map<String, SmartShuntData> shuntMap = new ConcurrentHashMap<>();
    private final Map<String, OrionData>      orionMap = new ConcurrentHashMap<>();

    private final Set<String> mpptRegistered  = ConcurrentHashMap.newKeySet();
    private final Set<String> shuntRegistered = ConcurrentHashMap.newKeySet();
    private final Set<String> orionRegistered = ConcurrentHashMap.newKeySet();

    // Zeitpunkt des zuletzt dekodierten Advertisements pro Gerät (MAC, normalisiert).
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    @Inject MeterRegistry registry;
    @Inject ReadingRepository repo;

    public void updateMppt(MpptData data) {
        String key = normalize(data.mac());
        mpptMap.put(key, data);
        lastSeen.put(key, Instant.now());
        if (mpptRegistered.add(key)) registerMpptGauges(key, data.name());
        repo.insert(data);
    }

    public void updateShunt(SmartShuntData data) {
        String key = normalize(data.mac());
        shuntMap.put(key, data);
        lastSeen.put(key, Instant.now());
        if (shuntRegistered.add(key)) registerShuntGauges(key, data.name());
        repo.insert(data);
    }

    public void updateOrion(OrionData data) {
        String key = normalize(data.mac());
        orionMap.put(key, data);
        lastSeen.put(key, Instant.now());
        if (orionRegistered.add(key)) registerOrionGauges(key, data.name());
        repo.insert(data);
    }

    public Optional<MpptData>       getMppt(String mac)  { return Optional.ofNullable(mpptMap.get(normalize(mac))); }
    public Optional<SmartShuntData> getShunt(String mac) { return Optional.ofNullable(shuntMap.get(normalize(mac))); }
    public Optional<OrionData>      getOrion(String mac) { return Optional.ofNullable(orionMap.get(normalize(mac))); }

    public Map<String, MpptData>       getAllMppt()  { return Map.copyOf(mpptMap); }
    public Map<String, SmartShuntData> getAllShunt() { return Map.copyOf(shuntMap); }
    public Map<String, OrionData>      getAllOrion() { return Map.copyOf(orionMap); }

    /** Zeitpunkt des zuletzt empfangenen Advertisements für diese MAC (falls je gesehen). */
    public Optional<Instant> getLastSeen(String mac) { return Optional.ofNullable(lastSeen.get(normalize(mac))); }
    public Map<String, Instant> getAllLastSeen()     { return Map.copyOf(lastSeen); }

    private void registerMpptGauges(String mac, String name) {
        Tags tags = Tags.of("mac", mac, "name", name);
        gauge("victron_mppt_battery_voltage_v", tags, mpptMap, mac, d -> ((MpptData) d).batteryVoltageV());
        gauge("victron_mppt_battery_current_a", tags, mpptMap, mac, d -> ((MpptData) d).batteryCurrentA());
        gauge("victron_mppt_panel_voltage_v",   tags, mpptMap, mac, d -> ((MpptData) d).panelVoltageV());
        gauge("victron_mppt_panel_power_w",     tags, mpptMap, mac, d -> ((MpptData) d).panelPowerW());
        gauge("victron_mppt_yield_today_wh",    tags, mpptMap, mac, d -> ((MpptData) d).yieldTodayWh());
        gauge("victron_mppt_charger_state",     tags, mpptMap, mac, d -> ((MpptData) d).chargerState());
        registerLastSeenGauge(mac, tags);
    }

    private void registerShuntGauges(String mac, String name) {
        Tags tags = Tags.of("mac", mac, "name", name);
        gauge("victron_shunt_voltage_v",   tags, shuntMap, mac, d -> ((SmartShuntData) d).batteryVoltageV());
        gauge("victron_shunt_current_a",   tags, shuntMap, mac, d -> ((SmartShuntData) d).batteryCurrentA());
        gauge("victron_shunt_soc_percent", tags, shuntMap, mac, d -> ((SmartShuntData) d).stateOfChargePercent());
        gauge("victron_shunt_consumed_ah", tags, shuntMap, mac, d -> ((SmartShuntData) d).consumedAh());
        gauge("victron_shunt_ttg_minutes", tags, shuntMap, mac, d -> {
            Integer ttg = ((SmartShuntData) d).timeToGoMinutes();
            return ttg == null ? Double.NaN : ttg;
        });
        gauge("victron_shunt_temperature_c", tags, shuntMap, mac, d -> {
            Double t = ((SmartShuntData) d).temperatureC();
            return t == null ? Double.NaN : t;
        });
        registerLastSeenGauge(mac, tags);
    }

    private void registerOrionGauges(String mac, String name) {
        Tags tags = Tags.of("mac", mac, "name", name);
        gauge("victron_orion_input_voltage_v",  tags, orionMap, mac, d -> ((OrionData) d).inputVoltageV());
        gauge("victron_orion_output_voltage_v", tags, orionMap, mac, d -> ((OrionData) d).outputVoltageV());
        gauge("victron_orion_input_current_a",  tags, orionMap, mac, d -> {
            Double i = ((OrionData) d).inputCurrentA();
            return i == null ? Double.NaN : i;
        });
        gauge("victron_orion_output_current_a", tags, orionMap, mac, d -> {
            Double i = ((OrionData) d).outputCurrentA();
            return i == null ? Double.NaN : i;
        });
        gauge("victron_orion_state", tags, orionMap, mac, d -> ((OrionData) d).state());
        registerLastSeenGauge(mac, tags);
    }

    /** Unix-Zeit (Sekunden) des zuletzt empfangenen Advertisements — für Staleness-Alarme in Prometheus. */
    private void registerLastSeenGauge(String mac, Tags tags) {
        gauge("victron_last_seen_epoch_seconds", tags, lastSeen, mac, d -> (double) ((Instant) d).getEpochSecond());
    }

    private <T> void gauge(String name, Tags tags, Map<String, T> map, String mac, ToDoubleFunction<Object> extract) {
        Gauge.builder(name, map, m -> {
            T d = m.get(mac);
            return d == null ? Double.NaN : extract.applyAsDouble(d);
        }).tags(tags).strongReference(true).register(registry);
    }

    private String normalize(String mac) {
        return mac.toUpperCase().replaceAll("[^A-F0-9]", "");
    }
}
