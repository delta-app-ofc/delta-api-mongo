package br.com.delta.delta_api_mongo.modules.alerts.security;

import br.com.delta.delta_api_mongo.modules.alerts.config.AlertsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import static br.com.delta.delta_api_mongo.modules.alerts.TestKeys.*;
import static org.assertj.core.api.Assertions.assertThat;

class AlertsFiltersTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC);

    private static UserJwtFilter userFilter() {
        return new UserJwtFilter(new JwtVerifier(new AlertsProperties(true, "",
                new AlertsProperties.Auth(PUBLIC_PEM, "delta-api", "delta-api", 30)), CLOCK));
    }

    private static ServiceKeyFilter keyFilter(String key) {
        return new ServiceKeyFilter(new AlertsProperties(true, key, new AlertsProperties.Auth("", "a", "a", 30)));
    }

    private static MockHttpServletResponse run(jakarta.servlet.Filter filter, MockHttpServletRequest request,
                                               AtomicInteger calls) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> calls.incrementAndGet());
        return response;
    }

    private static MockHttpServletRequest get(String path, String header, String value) {
        var request = new MockHttpServletRequest("GET", path);
        if (value != null) request.addHeader(header, value);
        return request;
    }

    @Test
    void aValidBearerTokenPassesAndExposesTheUserIdFromTheToken() throws Exception {
        var calls = new AtomicInteger();
        var request = get("/api/alerts", "Authorization", "Bearer " + token("212"));

        var response = run(userFilter(), request, calls);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(calls).hasValue(1);
        assertThat(request.getAttribute(UserJwtFilter.USER_ID)).isEqualTo(212);
    }

    @Test
    void aMissingOrInvalidTokenGetsAJson401WithoutCallingTheController() throws Exception {
        var calls = new AtomicInteger();

        var noHeader = run(userFilter(), get("/api/alerts/leak-summary", "X", null), calls);
        var wrongScheme = run(userFilter(), get("/api/alerts", "Authorization", "Basic abc"), calls);
        var garbage = run(userFilter(), get("/api/alerts", "Authorization", "Bearer lixo"), calls);

        for (var response : new MockHttpServletResponse[]{noHeader, wrongScheme, garbage}) {
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString()).contains("\"status\":401").contains("Autenticação necessária");
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void theUserFilterIgnoresOtherRoutesAndTheInternalOnes() throws Exception {
        var calls = new AtomicInteger();

        for (String path : new String[]{"/api/telemetry/consumption-summary", "/health", "/api/internal/alerts",
                "/api/alertsxyz"}) {
            assertThat(run(userFilter(), get(path, "X", null), calls).getStatus()).as(path).isEqualTo(200);
        }
        assertThat(calls).hasValue(4);
    }

    @Test
    void theUserIdCannotBeSuppliedByTheClient() throws Exception {
        var calls = new AtomicInteger();
        var request = get("/api/alerts", "Authorization", "Bearer " + token("5"));
        request.setParameter("user_id", "999");
        request.setAttribute(UserJwtFilter.USER_ID, 999);

        run(userFilter(), request, calls);

        assertThat(request.getAttribute(UserJwtFilter.USER_ID)).isEqualTo(5);
    }

    @Test
    void theServiceKeyFilterAcceptsOnlyTheConfiguredKey() throws Exception {
        var calls = new AtomicInteger();

        var ok = run(keyFilter("segredo"), get("/api/internal/alerts", "X-Service-Key", "segredo"), calls);
        var wrong = run(keyFilter("segredo"), get("/api/internal/alerts/resolve", "X-Service-Key", "errado"), calls);
        var missing = run(keyFilter("segredo"), get("/api/internal/alerts", "X", null), calls);

        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(wrong.getContentAsString()).contains("Chave de serviço");
        assertThat(calls).hasValue(1);
    }

    @Test
    void withoutAConfiguredKeyTheInternalRoutesStayClosed() throws Exception {
        var calls = new AtomicInteger();

        assertThat(run(keyFilter(""), get("/api/internal/alerts", "X-Service-Key", ""), calls).getStatus()).isEqualTo(401);
        assertThat(run(keyFilter(""), get("/api/internal/alerts", "X-Service-Key", "qualquer"), calls).getStatus())
                .isEqualTo(401);
        assertThat(calls).hasValue(0);
    }

    @Test
    void theServiceKeyFilterIgnoresUserRoutesAndAUserTokenDoesNotOpenInternalOnes() throws Exception {
        var calls = new AtomicInteger();

        assertThat(run(keyFilter("segredo"), get("/api/alerts", "X", null), calls).getStatus()).isEqualTo(200);
        var userToken = run(keyFilter("segredo"), get("/api/internal/alerts", "Authorization", "Bearer " + token("1")),
                calls);
        assertThat(userToken.getStatus()).isEqualTo(401);
    }
}
