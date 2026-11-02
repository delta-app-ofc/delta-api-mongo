package br.com.delta.delta_api_mongo.modules.alerts.dto.response;

/** Quantos alertas abertos foram encerrados (0 se já estava resolvido: a chamada é idempotente). */
public record ResolveAlertResponse(int resolved) {
}
