package de.mhome.victron.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "victron")
public interface VictronConfig {

    Ble ble();

    interface Ble {
        String adapter();

        @WithName("scan-interval")
        String scanInterval();

        /** When false (default) scanning stays off at boot and is started via the REST API. */
        @WithName("auto-start")
        @WithDefault("false")
        boolean autoStart();
    }
}
