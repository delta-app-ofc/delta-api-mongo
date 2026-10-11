package br.com.delta.delta_api_mongo.common.devicevalidation;

import br.com.delta.delta_api_mongo.common.config.DeviceValidationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceValidationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DeviceValidationConfig.class)
            .withPropertyValues("delta.device-validation.connect-timeout=2s", "delta.device-validation.response-timeout=5s");

    @Test
    void missingSqlUrlFailsStartup() {
        context.withPropertyValues("delta.device-validation.service-credential=delta_svc_" + "a".repeat(43))
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void missingServiceCredentialFailsStartup() {
        context.withPropertyValues("delta.device-validation.sql-api-base-url=http://localhost:8080")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void validConfigurationStartsWithoutContactingSql() {
        context.withPropertyValues("delta.device-validation.sql-api-base-url=http://localhost:8080",
                        "delta.device-validation.service-credential=delta_svc_" + "a".repeat(43))
                .run(application -> assertThat(application).hasNotFailed().hasSingleBean(SqlDeviceValidationClient.class));
    }
}
