package br.com.delta.delta_api_mongo.common.deviceauth;

import br.com.delta.delta_api_mongo.common.config.DeviceAuthConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceAuthConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DeviceAuthConfig.class)
            .withPropertyValues("delta.device-auth.connect-timeout=2s", "delta.device-auth.response-timeout=5s");

    @Test
    void missingSqlUrlFailsStartup() {
        context.withPropertyValues("delta.device-auth.service-credential=delta_svc_" + "a".repeat(43))
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void missingServiceCredentialFailsStartup() {
        context.withPropertyValues("delta.device-auth.sql-api-base-url=http://localhost:8080")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void validConfigurationStartsWithoutContactingSql() {
        context.withPropertyValues("delta.device-auth.sql-api-base-url=http://localhost:8080",
                        "delta.device-auth.service-credential=delta_svc_" + "a".repeat(43))
                .run(application -> assertThat(application).hasNotFailed().hasSingleBean(SqlDeviceAuthClient.class));
    }
}
