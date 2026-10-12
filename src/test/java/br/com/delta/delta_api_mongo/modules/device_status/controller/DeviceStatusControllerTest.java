package br.com.delta.delta_api_mongo.modules.device_status.controller;

import br.com.delta.delta_api_mongo.common.devicevalidation.SqlDeviceValidationClient;
import br.com.delta.delta_api_mongo.common.config.OpenApiConfig;
import br.com.delta.delta_api_mongo.common.exception.DeviceValidationUnavailableException;
import br.com.delta.delta_api_mongo.modules.device_status.document.DeviceStatusDocument;
import br.com.delta.delta_api_mongo.modules.device_status.mapper.DeviceStatusMapper;
import br.com.delta.delta_api_mongo.modules.device_status.repository.DeviceStatusIndexes;
import br.com.delta.delta_api_mongo.modules.device_status.repository.DeviceStatusRepository;
import br.com.delta.delta_api_mongo.modules.device_status.service.DeviceStatusService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeviceStatusController.class)
@Import({DeviceStatusService.class, DeviceStatusMapper.class, OpenApiConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class, SpringDocWebMvcConfiguration.class})
class DeviceStatusControllerTest {
    private static final String PATH = "/api/telemetry/device-status";
    private static final String DEVICE = "ESP32-SP-0912";
    private static final ObjectId ID = new ObjectId("60c72b2f9b1d8b2bad723456");
    private static final Instant PING = Instant.parse("2026-07-17T17:10:00Z");
    @Autowired private MockMvc mvc;
    @MockitoBean private DeviceStatusRepository repository;
    @MockitoBean private DeviceStatusIndexes indexes;
    @MockitoBean private SqlDeviceValidationClient client;

    private static String payload() {
        return """
                {"device_id":"ESP32-SP-0912","last_ping_at":"2026-07-17T17:10:00Z",
                 "wifi_signal_rssi":"good","firmware_version":"v1.2.3",
                 "connectivity_status":"online","unavailability_reason":null}
                """;
    }

    private static DeviceStatusDocument document() {
        return new DeviceStatusDocument(ID, DEVICE, PING, "good", "v1.2.3", "online", null);
    }

    @BeforeEach
    void setUp() {
        lenient().when(client.validate(DEVICE)).thenReturn(Optional.of(DEVICE));
        lenient().when(repository.saveLatest(any())).thenAnswer(invocation -> {
            DeviceStatusDocument document = invocation.getArgument(0);
            document.setId(ID);
            return document;
        });
    }

    @Test
    void receivesHardwarePayloadAndValidatesBeforeWriting() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(payload()))
                .andExpect(status().isOk()).andExpect(jsonPath("$._id").value(ID.toHexString()))
                .andExpect(jsonPath("$.device_id").value(DEVICE))
                .andExpect(jsonPath("$.last_ping_at").value(PING.toString()))
                .andExpect(jsonPath("$.wifi_signal_rssi").value("good"))
                .andExpect(jsonPath("$.firmware_version").value("v1.2.3"))
                .andExpect(jsonPath("$.connectivity_status").value("online"));
        var order = inOrder(client, indexes, repository);
        order.verify(client).validate(DEVICE);
        order.verify(indexes).ensureCreated();
        order.verify(repository).saveLatest(any());
    }

    @Test
    void usesCanonicalDeviceIdFromSqlAndAllowsOmittedReason() throws Exception {
        when(client.validate(DEVICE)).thenReturn(Optional.of("canonical-device"));
        mvc.perform(post(PATH).contentType("application/json")
                        .content(payload().replace(",\"unavailability_reason\":null", "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.device_id").value("canonical-device"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"device_id", "last_ping_at", "wifi_signal_rssi", "firmware_version", "connectivity_status"})
    void requiredFieldsCannotBeNull(String field) throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(payload());
        json.putNull(field);
        mvc.perform(post(PATH).contentType("application/json").content(mapper.writeValueAsString(json)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client, indexes, repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true", "[]", "{}", "\"   \"", "\"\""})
    void rejectsInvalidDeviceIdsBeforeSql(String value) throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(payload().replace("\"" + DEVICE + "\"", value)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client, indexes, repository);
    }

    @Test
    void rejectsMalformedDateAndOversizedValues() throws Exception {
        for (String body : java.util.List.of(payload().replace(PING.toString(), "bad-date"),
                payload().replace(DEVICE, "x".repeat(101)), payload().replace("good", "x".repeat(51)),
                payload().replace("v1.2.3", "x".repeat(101)), payload().replace("online", "x".repeat(51)),
                payload().replace("\"unavailability_reason\":null", "\"unavailability_reason\":\"" + "x".repeat(501) + "\""))) {
            mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(client, indexes, repository);
    }

    @Test
    void rejectedDeviceCannotWrite() throws Exception {
        when(client.validate(DEVICE)).thenReturn(Optional.empty());
        mvc.perform(post(PATH).contentType("application/json").content(payload())).andExpect(status().isForbidden());
        verifyNoInteractions(indexes, repository);
    }

    @Test
    void unavailableSqlCannotWrite() throws Exception {
        when(client.validate(DEVICE)).thenThrow(new DeviceValidationUnavailableException());
        mvc.perform(post(PATH).contentType("application/json").content(payload())).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(indexes, repository);
    }

    @Test
    void readsLatestWithoutSqlValidation() throws Exception {
        when(repository.findByDeviceId(DEVICE)).thenReturn(Optional.of(document()));
        mvc.perform(get(PATH).param("device_id", DEVICE)).andExpect(status().isOk())
                .andExpect(jsonPath("$._id").value(ID.toHexString()));
        verifyNoInteractions(client, indexes);
    }

    @Test
    void missingStatusReturnsNotFound() throws Exception {
        mvc.perform(get(PATH).param("device_id", DEVICE)).andExpect(status().isNotFound());
    }

    @Test
    void invalidQueriesDoNotReadDatabase() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("device_id", " ")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("device_id", "x".repeat(101))).andExpect(status().isBadRequest());
        verifyNoInteractions(client, indexes, repository);
    }

    @Test
    void swaggerExposesHardwareAndReadContracts() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].get.responses['404']").exists());
    }
}
