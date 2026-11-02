package br.com.delta.delta_api_mongo.modules.alerts.swagger;

import br.com.delta.delta_api_mongo.common.dto.ErrorResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.LeakSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;

@Tag(name = "Alertas", description = "Alertas do usuário autenticado (JWT do back-end no cabeçalho Authorization: Bearer). "
        + "O usuário vem do token; só os próprios alertas são acessíveis.")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Token ausente, inválido ou expirado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public interface AlertsSwagger {
    @Operation(summary = "Listar alertas do usuário",
            description = "Mais novos primeiro. Por padrão não traz os dispensados. Filtro opcional por severidade: "
                    + "high (Crítico), medium (Atenção) ou low (Informativo).")
    @ApiResponse(responseCode = "200", description = "Página de alertas")
    PagedModel<AlertResponse> list(
            @Parameter(description = "high, medium ou low; omitido = todos") String severity,
            @Parameter(description = "Inclui os alertas já dispensados") boolean includeDismissed,
            Integer userId, Pageable pageable);

    @Operation(summary = "Dispensar alerta",
            description = "Marca dismissed_at (botão X da tela). Idempotente. Alerta de outro usuário responde 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alerta dispensado"),
            @ApiResponse(responseCode = "404", description = "Alerta não encontrado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    AlertResponse dismiss(String id, Integer userId);

    @Operation(summary = "Resumo dos vazamentos das últimas 24 horas",
            description = "Litros por dia estimados e número de pontos (dispositivos) com vazamento. "
                    + "Não traz R$: o custo é calculado com a tarifa pela IA ou pelo app.")
    @ApiResponse(responseCode = "200", description = "Resumo")
    LeakSummaryResponse leakSummary(Integer userId);
}
