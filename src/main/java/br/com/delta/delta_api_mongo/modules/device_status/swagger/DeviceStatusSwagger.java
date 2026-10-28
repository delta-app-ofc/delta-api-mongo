package br.com.delta.delta_api_mongo.modules.device_status.swagger;

import br.com.delta.delta_api_mongo.common.dto.ErrorResponse;
import br.com.delta.delta_api_mongo.modules.device_status.dto.request.DeviceStatusRequest;
import br.com.delta.delta_api_mongo.modules.device_status.dto.response.DeviceStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Status do dispositivo", description = "Recebimento do hardware e consulta do último estado conhecido")
public interface DeviceStatusSwagger {
    @Operation(summary = "Receber status do hardware", description = "Mantém um documento por device_id informado, sem consultar cadastro ou situação na SQL. "
            + "O MongoDB gera _id. Pings antigos são ignorados e retornam o estado atual. "
            + "Datas iguais permitem substituir o estado. Datas têm precisão de milissegundos. "
            + "RSSI e conectividade são textos; battery_level é um inteiro de 0 a 100; "
            + "motivo de indisponibilidade é opcional. Não exige chave do hardware.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado atual do dispositivo", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Dados inválidos", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    DeviceStatusResponse receive(DeviceStatusRequest request);

    @Operation(summary = "Consultar estado atual por dispositivo", description = "Retorna o último estado recebido, sem inferir conectividade pelo tempo decorrido.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status encontrado", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "Dispositivo inválido", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Status não encontrado", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    DeviceStatusResponse findByDeviceId(@Parameter(description = "Identificador textual do dispositivo", example = "ESP32-SP-0912") String deviceId);
}
