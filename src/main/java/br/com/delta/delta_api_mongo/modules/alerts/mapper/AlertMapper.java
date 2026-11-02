package br.com.delta.delta_api_mongo.modules.alerts.mapper;

import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import org.springframework.stereotype.Component;

@Component
public class AlertMapper {
    public AlertResponse toResponse(AlertHistoryDocument alert) {
        return new AlertResponse(alert.getId().toHexString(), alert.getDeviceId(), alert.getPropertyId(),
                alert.getAlertType(), alert.getSeverity(), alert.getTitle(), alert.getMessage(), alert.getSource(),
                alert.getRule(), alert.getEvidence(), alert.getTriggeredAt(), alert.getResolvedAt(),
                alert.getDismissedAt());
    }
}
