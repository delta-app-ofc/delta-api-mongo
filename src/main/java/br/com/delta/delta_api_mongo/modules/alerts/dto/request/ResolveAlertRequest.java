package br.com.delta.delta_api_mongo.modules.alerts.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Pede o encerramento do alerta aberto com esta chave de deduplicação (a condição deixou de existir). */
public record ResolveAlertRequest(
        @NotBlank @Size(max = 200) @JsonProperty("dedup_key") String dedupKey
) {
}
