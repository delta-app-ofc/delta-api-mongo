package br.com.delta.delta_api_mongo.modules.alerts.controller;

import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.alerts.TestKeys;
import br.com.delta.delta_api_mongo.modules.alerts.config.AlertsConfig;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.LeakSummaryResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.ResolveAlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.security.JwtVerifier;
import br.com.delta.delta_api_mongo.modules.alerts.security.ServiceKeyFilter;
import br.com.delta.delta_api_mongo.modules.alerts.security.UserJwtFilter;
import br.com.delta.delta_api_mongo.modules.alerts.service.AlertsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AlertsController.class, InternalAlertsController.class}, properties = {
        "delta.alerts.enabled=true", "delta.alerts.service-key=segredo-de-teste"})
@Import({AlertsConfig.class, JwtVerifier.class, UserJwtFilter.class, ServiceKeyFilter.class})
class AlertsControllerTest {
    private static final String ID = "6ab94eaad79d853341d2605c";
    private static final Instant T = Instant.parse("2026-11-02T12:00:00Z");

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("delta.alerts.auth.public-key", () -> TestKeys.PUBLIC_PEM);
    }

    @Autowired private MockMvc mvc;
    @MockitoBean private AlertsService service;

    private static AlertResponse response() {
        return new AlertResponse(ID, "DEV-1", 10, "vazamento_continuo", "high", "Possível vazamento",
                "Fluxo contínuo de 1,8 L/min há 47 min sem pausa", "detector", "continuous_flow",
                Map.of("liters_per_day_estimate", 2592), T, null, null);
    }

    private static String bearer() throws Exception {
        return "Bearer " + TestKeys.liveToken("212");
    }

    private static final String BODY = """
            {"user_id":212,"alert_type":"previsao_ultrapassagem_meta","severity":"medium",
             "title":"Previsão de ultrapassagem de meta","message":"Ritmo projeta 108% da meta",
             "source":"gluh","dedup_key":"gluh:212:previsao_ultrapassagem_meta:2026-11","property_id":1,
             "evidence":{"projected_percent":108}}""";

    // ---------------- rotas do usuário ----------------

    @Test
    void listRequiresATokenAndDoesNotTouchTheService() throws Exception {
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401));
        mvc.perform(get("/api/alerts").header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void listReturnsThePageOfTheUserInTheToken() throws Exception {
        when(service.list(eq(212), eq(null), eq(false), any()))
                .thenReturn(new PagedModel<>(new PageImpl<>(List.of(response()))));

        mvc.perform(get("/api/alerts").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("content[0]._id").value(ID))
                .andExpect(jsonPath("content[0].alert_type").value("vazamento_continuo"))
                .andExpect(jsonPath("content[0].title").value("Possível vazamento"))
                .andExpect(jsonPath("content[0].evidence.liters_per_day_estimate").value(2592))
                .andExpect(jsonPath("content[0].triggered_at").value("2026-11-02T12:00:00Z"));
    }

    @Test
    void listForwardsTheSeverityFilterAndTheDismissedFlag() throws Exception {
        when(service.list(eq(212), eq("high"), eq(true), any()))
                .thenReturn(new PagedModel<>(new PageImpl<>(List.of())));

        mvc.perform(get("/api/alerts?severity=high&include_dismissed=true").header("Authorization", bearer()))
                .andExpect(status().isOk());

        verify(service).list(eq(212), eq("high"), eq(true), any());
    }

    @Test
    void aUserIdInTheQueryStringNeverReplacesTheOneFromTheToken() throws Exception {
        when(service.list(eq(212), eq(null), eq(false), any()))
                .thenReturn(new PagedModel<>(new PageImpl<>(List.of())));

        mvc.perform(get("/api/alerts?user_id=999&userId=999").header("Authorization", bearer()))
                .andExpect(status().isOk());

        verify(service).list(eq(212), eq(null), eq(false), any());
        verify(service, never()).list(eq(999), any(), any(Boolean.class), any());
    }

    @Test
    void anInvalidFilterBecomesA400() throws Exception {
        when(service.list(eq(212), eq("x"), eq(false), any())).thenThrow(new IllegalArgumentException("severity inválida"));

        mvc.perform(get("/api/alerts?severity=x").header("Authorization", bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("message").value("severity inválida"));
    }

    @Test
    void dismissMarksTheAlertOfTheTokenUser() throws Exception {
        when(service.dismiss(212, ID)).thenReturn(response());

        mvc.perform(patch("/api/alerts/" + ID + "/dismiss").header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("_id").value(ID));
    }

    @Test
    void dismissOfAnotherUsersAlertIsA404AndNeedsAToken() throws Exception {
        when(service.dismiss(212, ID)).thenThrow(new ResourceNotFoundException("Alerta não encontrado."));

        mvc.perform(patch("/api/alerts/" + ID + "/dismiss").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/alerts/" + ID + "/dismiss")).andExpect(status().isUnauthorized());
    }

    @Test
    void leakSummaryReturnsLitersPerDayAndPointsWithoutMoney() throws Exception {
        when(service.leakSummary(212)).thenReturn(new LeakSummaryResponse(1900, 2, 24, T));

        mvc.perform(get("/api/alerts/leak-summary").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("liters_per_day").value(1900))
                .andExpect(jsonPath("points").value(2))
                .andExpect(jsonPath("window_hours").value(24))
                .andExpect(jsonPath("cost").doesNotExist());
    }

    // ---------------- rotas internas ----------------

    @Test
    void internalRoutesNeedTheServiceKeyAndIgnoreUserTokens() throws Exception {
        mvc.perform(post("/api/internal/alerts").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/alerts").header("X-Service-Key", "errada")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/internal/alerts").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void createReturns201WhenCreatedAnd200WhenItAlreadyExisted() throws Exception {
        when(service.create(any())).thenReturn(new AlertsService.CreateResult(response(), true))
                .thenReturn(new AlertsService.CreateResult(response(), false));

        mvc.perform(post("/api/internal/alerts").header("X-Service-Key", "segredo-de-teste")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("_id").value(ID));
        mvc.perform(post("/api/internal/alerts").header("X-Service-Key", "segredo-de-teste")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
    }

    @Test
    void createValidatesTheBodyBeforeCallingTheService() throws Exception {
        mvc.perform(post("/api/internal/alerts").header("X-Service-Key", "segredo-de-teste")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"sem campos obrigatórios\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/internal/alerts").header("X-Service-Key", "segredo-de-teste")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"user_id\":212", "\"user_id\":-3")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void aBusinessRuleViolationFromTheServiceBecomesA400() throws Exception {
        when(service.create(any())).thenThrow(new IllegalArgumentException("severity high só é permitido para limite_ultrapassado"));

        mvc.perform(post("/api/internal/alerts").header("X-Service-Key", "segredo-de-teste")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("medium", "high")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("message").value(org.hamcrest.Matchers.containsString("limite_ultrapassado")));
    }

    @Test
    void resolveClosesByDedupKey() throws Exception {
        when(service.resolve("gluh:212:previsao_ultrapassagem_meta:2026-11")).thenReturn(new ResolveAlertResponse(1));

        mvc.perform(post("/api/internal/alerts/resolve").header("X-Service-Key", "segredo-de-teste")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dedup_key\":\"gluh:212:previsao_ultrapassagem_meta:2026-11\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("resolved").value(1));
        mvc.perform(post("/api/internal/alerts/resolve").header("X-Service-Key", "segredo-de-teste")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }
}
