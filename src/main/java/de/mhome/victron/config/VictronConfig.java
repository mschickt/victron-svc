package de.mhome.victron.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import java.util.List;

@ConfigMapping(prefix = "victron")
public interface VictronConfig {

    Ble ble();

    List<DeviceConfig> devices();

    interface Ble {
        String adapter();
        @WithName("scan-interval-seconds")
        int scanIntervalSeconds();
    }

    interface DeviceConfig {
        String mac();
        String name();
        DeviceType type();
        @WithName("advertisement-key")
        String advertisementKey();
    }

    enum DeviceType {
        MPPT, SMART_SHUNT, ORION_TR
    }
}
