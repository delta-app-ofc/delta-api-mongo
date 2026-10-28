package br.com.delta.delta_api_mongo.common.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final MongoTemplate mongo;

    public HealthController(@Qualifier("telemetryMongoTemplate") MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @GetMapping("/health")
    @Operation(summary = "Verificar a saúde da API e do MongoDB de telemetria")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "API e MongoDB disponíveis"),
            @ApiResponse(responseCode = "503", description = "MongoDB indisponível")
    })
    public ResponseEntity<HealthResponse> health() {
        try {
            mongo.executeCommand(new Document("ping", 1));
            return ResponseEntity.ok(new HealthResponse("UP"));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse("DOWN"));
        }
    }

    public record HealthResponse(String status) {}
}
