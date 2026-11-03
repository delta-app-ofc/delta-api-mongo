package br.com.delta.delta_api_mongo.modules.consumption.controller;

import br.com.delta.delta_api_mongo.common.config.OpenApiConfig;
import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.consumption.mapper.ConsumptionSummaryMapper;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryIndexes;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import br.com.delta.delta_api_mongo.modules.consumption.service.ConsumptionSummaryService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocPageableConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConsumptionSummaryController.class)
@Import({ConsumptionSummaryService.class, ConsumptionSummaryMapper.class, OpenApiConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, SpringDocPageableConfiguration.class})
class ConsumptionSummaryControllerTest {
    private static final String PATH = "/api/telemetry/consumption-summary";
    private static final String DEVICE = "ESP32-SP-0001";
    private static final ObjectId ID = new ObjectId("6ab94eaad79d853341d2605c");
    private static final Instant START = Instant.parse("2026-10-10T17:00:00Z");
    private static final Instant END = START.plusSeconds(300);
    @Autowired private MockMvc mvc;
    @MockitoBean private ConsumptionSummaryRepository repository;
    @MockitoBean private ConsumptionSummaryIndexes indexes;

    private static String payload() {
        return """
                {"device_id":"ESP32-SP-0001","window_started_at":"2026-10-10T17:00:00Z",
                 "window_finished_at":"2026-10-10T17:05:00Z","consumption_liters":14.4,"lpm_average":2.88}
                """;
    }

    @BeforeEach
    void setUp() {
        lenient().when(repository.insert(any(ConsumptionSummaryDocument.class))).thenAnswer(invocation -> {
            ConsumptionSummaryDocument document = invocation.getArgument(0);
            document.setId(ID);
            return document;
        });
    }

    @Test
    void validPayloadCreatesWithoutHardwareCredential() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(payload()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + PATH + "/" + ID))
                .andExpect(jsonPath("$._id").value(ID.toHexString()))
                .andExpect(jsonPath("$.device_id").value(DEVICE))
                .andExpect(jsonPath("$.consumption_liters").value(14.4))
                .andExpect(jsonPath("$.lpm_average").value(2.88))
                .andExpect(jsonPath("$.api_key").doesNotExist());
        var order = inOrder(indexes, repository);
        order.verify(indexes).ensureCreated();
        order.verify(repository).insert(any(ConsumptionSummaryDocument.class));
    }

    @Test
    void acceptsZeroConsumptionAndOmittedFlow() throws Exception {
        String body = payload().replace("14.4", "0").replace(",\"lpm_average\":2.88", "");
        mvc.perform(post(PATH).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.consumption_liters").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "inactive", "Esp-MixedCase-01"})
    void acceptsProvidedDeviceIdentityWithoutRegistryLookup(String deviceId) throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(payload().replace(DEVICE, deviceId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.device_id").value(deviceId));
        verify(repository).insert(argThat((ConsumptionSummaryDocument document) -> document.getDeviceId().equals(deviceId)));
    }

    @Test
    void duplicateWindowReturnsConflict() throws Exception {
        when(repository.insert(any(ConsumptionSummaryDocument.class))).thenThrow(new DuplicateKeyException("duplicate"));
        mvc.perform(post(PATH).contentType("application/json").content(payload()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \"", "123", "true", "[]", "{}"})
    void invalidDeviceIdNeverWrites(String value) throws Exception {
        String body = payload().replace("\"" + DEVICE + "\"", value);
        mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(indexes, repository);
    }

    @Test
    void missingOrLongDeviceIdNeverWrites() throws Exception {
        for (String body : List.of(payload().replace("\"device_id\":\"" + DEVICE + "\",", ""),
                payload().replace(DEVICE, "a".repeat(101)))) {
            mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(indexes, repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"device_id", "window_started_at", "window_finished_at", "consumption_liters"})
    void requiredFieldsCannotBeNull(String field) throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(payload());
        json.putNull(field);
        mvc.perform(post(PATH).contentType("application/json").content(mapper.writeValueAsString(json)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(indexes, repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"negative_consumption", "negative_flow", "equal_window", "reversed_window", "invalid_date"})
    void invalidReadingsNeverWrite(String scenario) throws Exception {
        String body = switch (scenario) {
            case "negative_consumption" -> payload().replace("14.4", "-1");
            case "negative_flow" -> payload().replace("2.88", "-1");
            case "equal_window" -> payload().replace("17:05:00Z", "17:00:00Z");
            case "reversed_window" -> payload().replace("17:05:00Z", "16:55:00Z");
            default -> payload().replace("2026-10-10T17:05:00Z", "not-a-date");
        };
        mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(indexes, repository);
    }

    @Test
    void readsById() throws Exception {
        when(repository.findById(ID)).thenReturn(Optional.of(new ConsumptionSummaryDocument(ID, DEVICE, START, END, 14.4, 2.88)));
        mvc.perform(get(PATH + "/" + ID)).andExpect(status().isOk()).andExpect(jsonPath("$.device_id").value(DEVICE));
        verifyNoInteractions(indexes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"consumption_liters", "lpm_average"})
    void rejectsNonFiniteReadingsBeforeWriting(String field) throws Exception {
        String body = field.equals("consumption_liters")
                ? payload().replace("14.4", "1e309") : payload().replace("2.88", "1e309");
        mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(indexes, repository);
    }

    @Test
    void differentiatesBadIdAndMissingSummary() throws Exception {
        mvc.perform(get(PATH + "/invalid")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
        mvc.perform(get(PATH + "/" + ID)).andExpect(status().isNotFound());
    }

    @Test
    void bindsPeriodAndPaginationWithStableOrder() throws Exception {
        var pageable = PageRequest.of(1, 10);
        var document = new ConsumptionSummaryDocument(ID, DEVICE, START, END, 14.4, null);
        when(repository.findByDeviceIdAndWindowStartedAtGreaterThanEqualAndWindowStartedAtLessThanOrderByWindowStartedAtDescIdDesc(
                DEVICE, START, END, pageable)).thenReturn(new PageImpl<>(List.of(document), pageable, 11));
        mvc.perform(get(PATH).param("device_id", DEVICE).param("start", START.toString()).param("end", END.toString())
                        .param("page", "1").param("size", "10").param("sort", "consumptionLiters,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0]._id").value(ID.toHexString()))
                .andExpect(jsonPath("$.page.number").value(1)).andExpect(jsonPath("$.page.totalElements").value(11));
        verifyNoInteractions(indexes);
    }

    @Test
    void invalidQueryNeverAccessesRepository() throws Exception {
        mvc.perform(get(PATH).param("device_id", DEVICE)).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("device_id", DEVICE).param("start", START.toString()).param("end", START.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("device_id", DEVICE).param("start", START.toString()).param("end", END.toString())
                        .param("size", "101")).andExpect(status().isBadRequest());
        verifyNoInteractions(indexes, repository);
    }

    @Test
    void swaggerDescribesContractWithoutHardwareKey() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['403']").doesNotExist())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['503']").doesNotExist())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.security").doesNotExist());
    }
}
