package br.com.delta.delta_api_mongo.modules.pulse.controller;

import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.pulse.dto.request.PulseRequest;
import br.com.delta.delta_api_mongo.modules.pulse.dto.response.PulseResponse;
import br.com.delta.delta_api_mongo.modules.pulse.service.PulseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PulseController.class)
class PulseControllerTest {

    private static final String PATH = "/api/telemetry/pulses";
    private static final String ID = "6ab94eaad79d853341d2605c";
    private static final Instant SENT_AT = Instant.parse("2026-09-27T17:13:06Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PulseService service;

    @Test
    void createsBatchAndReturnsLocationWithSnakeCaseJson() throws Exception {
        var pulse = new PulseResponse.PulseItemResponse(SENT_AT.minusSeconds(30), 1000L, 0L);
        var response = new PulseResponse(ID, "ESP32-SP-2929", SENT_AT, 5, 1, List.of(pulse));
        when(service.create(any(PulseRequest.class))).thenReturn(response);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "device_id": "ESP32-SP-2929",
                  "sent_at": "2026-09-27T17:13:06Z",
                  "window_minutes": 5,
                  "total_pulses": 1,
                  "pulses": [{"pulsed_at": "2026-09-27T17:12:36Z", "ms_since_boot": 1000, "delta_ms": 0}]
                }
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + PATH + "/" + ID))
                .andExpect(jsonPath("$._id").value(ID))
                .andExpect(jsonPath("$.device_id").value("ESP32-SP-2929"))
                .andExpect(jsonPath("$.pulses[0].ms_since_boot").value(1000));

        verify(service).create(new PulseRequest("ESP32-SP-2929", SENT_AT, 5, 1,
                List.of(new PulseRequest.PulseItemRequest(SENT_AT.minusSeconds(30), 1000L, 0L))));
    }

    @Test
    void rejectsInvalidNestedPulseBeforeCallingService() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "device_id": "ESP32-SP-2929",
                  "sent_at": "2026-09-27T17:13:06Z",
                  "window_minutes": 5,
                  "total_pulses": 1,
                  "pulses": [{"pulsed_at": "2026-09-27T17:12:36Z", "ms_since_boot": -1, "delta_ms": 0}]
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void returnsBatchById() throws Exception {
        when(service.findById(ID)).thenReturn(new PulseResponse(ID, "ESP32-SP-2929", SENT_AT, 5, 0, List.of()));

        mockMvc.perform(get(PATH + "/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._id").value(ID));
    }

    @Test
    void returnsNotFoundForMissingBatch() throws Exception {
        when(service.findById(ID)).thenThrow(new ResourceNotFoundException("Lote de pulsos não encontrado."));

        mockMvc.perform(get(PATH + "/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void bindsDeviceDatesAndPagination() throws Exception {
        var start = SENT_AT.minusSeconds(3600);
        var pageable = PageRequest.of(1, 10);
        when(service.findByDeviceAndPeriod("ESP32-SP-2929", start, SENT_AT, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        mockMvc.perform(get(PATH).param("device_id", "ESP32-SP-2929")
                        .param("start", start.toString()).param("end", SENT_AT.toString())
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(10));

        verify(service).findByDeviceAndPeriod("ESP32-SP-2929", start, SENT_AT, pageable);
    }

    @Test
    void rejectsMissingQueryParameters() throws Exception {
        mockMvc.perform(get(PATH).param("device_id", "ESP32-SP-2929"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
