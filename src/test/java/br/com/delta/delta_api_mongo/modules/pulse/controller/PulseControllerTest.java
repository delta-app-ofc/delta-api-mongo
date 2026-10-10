package br.com.delta.delta_api_mongo.modules.pulse.controller;

import br.com.delta.delta_api_mongo.common.config.OpenApiConfig;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.pulse.dto.request.PulseRequest;
import br.com.delta.delta_api_mongo.modules.pulse.dto.response.PulseResponse;
import br.com.delta.delta_api_mongo.modules.pulse.service.PulseService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocPageableConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PulseController.class)
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, SpringDocPageableConfiguration.class})
class PulseControllerTest {
    private static final String PATH = "/api/telemetry/pulses";
    private static final String ID = "6ab94eaad79d853341d2605c";
    private static final Instant SENT_AT = Instant.parse("2026-09-27T17:13:06Z");
    @Autowired private MockMvc mvc;
    @MockitoBean private PulseService service;

    @Test
    void documentsValidationWithoutHardwareCredentials() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.summary").value("Registrar um lote de pulsos"))
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.security").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.deviceApiKey").doesNotExist())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['401']").doesNotExist())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['" + PATH + "'].get.parameters[?(@.name == 'page')]").isNotEmpty());
    }

    @Test
    void createsWithoutHardwareAuthorization() throws Exception {
        when(service.create(any(PulseRequest.class)))
                .thenReturn(new PulseResponse(ID, "ESP00321", SENT_AT, 5, 0, List.of()));
        mvc.perform(post(PATH).contentType("application/json").content("""
                {"device_id":"ESP00321","sent_at":"2026-09-27T17:13:06Z",
                 "window_minutes":5,"total_pulses":0,"pulses":[]}
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + PATH + "/" + ID))
                .andExpect(jsonPath("$._id").value(ID));
        verify(service).create(new PulseRequest("ESP00321", SENT_AT, 5, 0, List.of()));
    }

    @Test
    void rejectsInvalidNestedPulseBeforeService() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content("""
                {"device_id":"ESP00321","sent_at":"2026-09-27T17:13:06Z","window_minutes":5,
                 "total_pulses":1,"pulses":[
                 {"pulsed_at":"2026-09-27T17:12:36Z","ms_since_boot":-1,"delta_ms":0}]}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returnsBatchById() throws Exception {
        when(service.findById(ID)).thenReturn(new PulseResponse(ID, "ESP00321", SENT_AT, 5, 0, List.of()));
        mvc.perform(get(PATH + "/" + ID)).andExpect(status().isOk()).andExpect(jsonPath("$._id").value(ID));
    }

    @Test
    void returnsNotFoundForMissingBatch() throws Exception {
        when(service.findById(ID)).thenThrow(new ResourceNotFoundException("Lote de pulsos não encontrado."));
        mvc.perform(get(PATH + "/" + ID)).andExpect(status().isNotFound());
    }

    @Test
    void bindsDeviceDatesAndPagination() throws Exception {
        var start = SENT_AT.minusSeconds(3600);
        var pageable = PageRequest.of(1, 10);
        when(service.findByDeviceAndPeriod("ESP00321", start, SENT_AT, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        mvc.perform(get(PATH).param("device_id", "ESP00321").param("start", start.toString())
                        .param("end", SENT_AT.toString()).param("page", "1").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(10));
    }

    @Test
    void rejectsMissingQueryParameters() throws Exception {
        mvc.perform(get(PATH).param("device_id", "ESP00321")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
