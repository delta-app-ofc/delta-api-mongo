package br.com.delta.delta_api_mongo.modules.pulse.swagger;

import br.com.delta.delta_api_mongo.common.deviceauth.AuthenticatedDevice;
import br.com.delta.delta_api_mongo.common.dto.ErrorResponse;
import br.com.delta.delta_api_mongo.modules.pulse.dto.request.PulseRequest;
import br.com.delta.delta_api_mongo.modules.pulse.dto.response.PulseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

@Tag(name = "Pulsos", description = "Recebimento e consulta de lotes de telemetria")
public interface PulseSwagger {

    @Operation(summary = "Registrar um lote de pulsos", security = @SecurityRequirement(name = "deviceApiKey"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Lote registrado", useReturnTypeSchema = true,
                    headers = @Header(name = "Location", description = "Endereço para consultar o lote",
                            schema = @Schema(type = "string", format = "uri"))),
            @ApiResponse(responseCode = "400", description = "Dados do lote inválidos",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Dispositivo não autenticado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Permissão de escrita ausente",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Validação SQL indisponível",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<PulseResponse> create(PulseRequest request, @Parameter(hidden = true) AuthenticatedDevice device);

    @Operation(summary = "Consultar um lote pelo identificador")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lote encontrado", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Identificador inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Lote não encontrado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    PulseResponse findById(
            @Parameter(description = "ObjectId do lote", example = "6ab94eaad79d853341d2605c") String id
    );

    @Operation(summary = "Consultar lotes por dispositivo e período",
            description = "Retorna os lotes mais recentes primeiro. A paginação começa em zero e usa 20 itens por padrão.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de lotes", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Dispositivo ou período inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    PagedModel<PulseResponse> findByDeviceAndPeriod(
            @Parameter(description = "Identificador do dispositivo", example = "ESP32-SP-2929") String deviceId,
            @Parameter(description = "Início inclusivo do período", example = "2026-09-27T17:00:00Z") Instant start,
            @Parameter(description = "Fim exclusivo do período", example = "2026-09-27T18:00:00Z") Instant end,
            @ParameterObject Pageable pageable
    );
}
