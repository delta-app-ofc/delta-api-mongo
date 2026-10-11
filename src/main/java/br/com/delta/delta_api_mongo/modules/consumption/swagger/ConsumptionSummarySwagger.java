package br.com.delta.delta_api_mongo.modules.consumption.swagger;

import br.com.delta.delta_api_mongo.common.dto.ErrorResponse;
import br.com.delta.delta_api_mongo.modules.consumption.dto.request.ConsumptionSummaryRequest;
import br.com.delta.delta_api_mongo.modules.consumption.dto.response.ConsumptionSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import java.time.Instant;

@Tag(name = "Resumo de consumo", description = "Recebimento direto do hardware e consulta de consumo por janela")
public interface ConsumptionSummarySwagger {
    @Operation(summary = "Registrar resumo de consumo",
            description = "device_id deve estar cadastrado e ativo na SQL. Não exige chave do hardware. "
                    + "A mesma janela do mesmo dispositivo não pode ser registrada duas vezes. Vazão média é opcional.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Resumo registrado", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Dados ou janela inválidos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Dispositivo não cadastrado ou inativo",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Janela já registrada para o dispositivo",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Validação SQL indisponível",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<ConsumptionSummaryResponse> create(ConsumptionSummaryRequest request);

    @Operation(summary = "Consultar resumo pelo identificador")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resumo encontrado", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Identificador inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Resumo não encontrado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ConsumptionSummaryResponse findById(@Parameter(description = "ObjectId do resumo") String id);

    @Operation(summary = "Consultar consumo por dispositivo e período",
            description = "Filtra pelo início da janela: start inclusivo e end exclusivo. "
                    + "Ordena por início decrescente. Páginas começam em zero, com 20 itens por padrão e máximo de 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de resumos", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Filtro ou paginação inválidos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    PagedModel<ConsumptionSummaryResponse> findByDeviceAndPeriod(
            @Parameter(description = "Identificador textual do dispositivo") String deviceId,
            @Parameter(description = "Início inclusivo", example = "2026-10-10T17:00:00Z") Instant start,
            @Parameter(description = "Fim exclusivo", example = "2026-10-10T18:00:00Z") Instant end,
            @ParameterObject Pageable pageable);
}
