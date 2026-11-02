package br.com.delta.delta_api_mongo.modules.alerts.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Resposta 401 no mesmo formato do ErrorResponse, escrita pelos filtros (antes do controller). */
final class UnauthorizedResponse {
    private UnauthorizedResponse() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        String body = "{\"timestamp\":\"" + Instant.now() + "\",\"status\":401,\"error\":\"Unauthorized\","
                + "\"message\":\"" + escape(message) + "\",\"path\":\"" + escape(request.getRequestURI()) + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
