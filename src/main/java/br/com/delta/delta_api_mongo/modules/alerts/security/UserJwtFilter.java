package br.com.delta.delta_api_mongo.modules.alerts.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Exige o JWT do usuário nas rotas /api/alerts/**. O id do usuário vem do token (atributo USER_ID da requisição),
 * nunca da URL ou do corpo. As demais rotas da API (telemetria, status, health) não são afetadas.
 */
@Component
@ConditionalOnProperty(prefix = "delta.alerts", name = "enabled", havingValue = "true")
public class UserJwtFilter extends OncePerRequestFilter {
    public static final String USER_ID = "delta.alerts.userId";
    private static final String PREFIX = "/api/alerts";

    private final JwtVerifier verifier;

    public UserJwtFilter(JwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/") || path.startsWith("/api/detection/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        var userId = verifier.verify(token);
        if (userId.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer");
            UnauthorizedResponse.write(request, response, "Autenticação necessária ou token inválido/expirado.");
            return;
        }
        request.setAttribute(USER_ID, userId.get());
        chain.doFilter(request, response);
    }
}
