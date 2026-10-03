package br.com.delta.delta_api_mongo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "springdotenv.directory=src/test/resources",
        "springdotenv.filename=dotenv-test.properties"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeltaApiMongoApplicationTests {

    @Autowired
    @Qualifier("telemetryMongoTemplate")
    private MongoTemplate telemetryMongoTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void loadsDotenvFileAsSpringPropertySource() {
        assertThat(environment.resolveRequiredPlaceholders("${DELTA_DOTENV_TEST_VALUE}"))
                .isEqualTo("dotenv-loaded");
    }

    @Test
    void contextLoadsWithTelemetryMongoDatabase() {
        assertThat(telemetryMongoTemplate.getDb().getName()).isEqualTo("db_delta_telemetry_test");
    }

    @Test
    void exposesOpenApiDocumentation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Delta Mongo API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"));
    }
}
