package br.com.delta.delta_api_mongo.modules.alerts.swagger;

import br.com.delta.delta_api_mongo.common.dto.ErrorResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.request.CreateAlertRequest;
import br.com.delta.delta_api_mongo.modules.alerts.dto.request.ResolveAlertRequest;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.ResolveAlertResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Alertas (interno)", description = "Escrita de alertas pela IA e pelo sistema. Exige o cabeçalho "
        + "X-Service-Key; não usa o token de usuário. Os alertas do detector são gravados internamente.")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Chave de serviço ausente ou inválida",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public interface InternalAlertsSwagger {
    @Operation(summary = "Criar alerta (idempotente por dedup_key)",
            description = "source gluh ou system. Severidade high só para limite_ultrapassado enviado pelo gluh. "
                    + "Se já existe um alerta aberto com a mesma dedup_key, devolve o existente com 200.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Alerta criado"),
            @ApiResponse(responseCode = "200", description = "Já existia um alerta aberto com esta dedup_key"),
            @ApiResponse(responseCode = "400", description = "Dados inválidos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "dedup_key aberta para outro usuário",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<AlertResponse> create(CreateAlertRequest request);

    @Operation(summary = "Encerrar alerta pela dedup_key",
            description = "Preenche resolved_at do alerta aberto com a chave. Idempotente: resolved = 0 se não havia aberto.")
    @ApiResponse(responseCode = "200", description = "Quantidade de alertas encerrados")
    ResolveAlertResponse resolve(ResolveAlertRequest request);
}
