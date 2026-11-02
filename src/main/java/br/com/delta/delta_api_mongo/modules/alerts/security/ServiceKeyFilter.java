package br.com.delta.delta_api_mongo.modules.alerts.security;

import br.com.delta.delta_api_mongo.modules.alerts.config.AlertsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Chamadas entre serviços (a IA) nas rotas /api/internal/alerts/**: o cabeçalho X-Service-Key precisa ser igual
 * a ALERTS_SERVICE_KEY. Sem a variável configurada, as rotas internas ficam fechadas para todos.
 */
@Component
@ConditionalOnProperty(prefix = "delta.alerts", name = "enabled", havingValue = "true")
public class ServiceKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Service-Key";
    private static final String PREFIX = "/api/internal/alerts";

    private final String expectedKey;

    public ServiceKeyFilter(AlertsProperties properties) {
        this.expectedKey = properties.serviceKey();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!validKey(request.getHeader(HEADER))) {
            UnauthorizedResponse.write(request, response, "Chave de serviço ausente ou inválida.");
            return;
        }
        chain.doFilter(request, response);
    }

    // Comparação em tempo constante para não vazar a chave por diferença de tempo.
    private boolean validKey(String received) {
        if (expectedKey == null || expectedKey.isBlank() || received == null) return false;
        return MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8));
    }
}
