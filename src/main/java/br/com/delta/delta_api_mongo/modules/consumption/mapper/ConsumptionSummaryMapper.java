package br.com.delta.delta_api_mongo.modules.consumption.mapper;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.consumption.dto.request.ConsumptionSummaryRequest;
import br.com.delta.delta_api_mongo.modules.consumption.dto.response.ConsumptionSummaryResponse;
import org.springframework.stereotype.Component;

@Component
public class ConsumptionSummaryMapper {
    public ConsumptionSummaryDocument toDocument(ConsumptionSummaryRequest request, String validatedDeviceId) {
        return new ConsumptionSummaryDocument(null, validatedDeviceId, request.windowStartedAt(),
                request.windowFinishedAt(), request.consumptionLiters(), request.lpmAverage());
    }

    public ConsumptionSummaryResponse toResponse(ConsumptionSummaryDocument document) {
        return new ConsumptionSummaryResponse(document.getId().toHexString(), document.getDeviceId(),
                document.getWindowStartedAt(), document.getWindowFinishedAt(), document.getConsumptionLiters(),
                document.getLpmAverage());
    }
}
