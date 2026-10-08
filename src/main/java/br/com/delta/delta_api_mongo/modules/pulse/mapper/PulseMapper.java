package br.com.delta.delta_api_mongo.modules.pulse.mapper;

import br.com.delta.delta_api_mongo.modules.pulse.document.PulseDocument;
import br.com.delta.delta_api_mongo.modules.pulse.document.PulseEntry;
import br.com.delta.delta_api_mongo.modules.pulse.dto.request.PulseRequest;
import br.com.delta.delta_api_mongo.modules.pulse.dto.response.PulseResponse;
import org.springframework.stereotype.Component;

@Component
public class PulseMapper {

    public PulseDocument toDocument(PulseRequest request, String authenticatedDeviceId) {
        return new PulseDocument(
                null,
                authenticatedDeviceId,
                request.sentAt(),
                request.windowMinutes(),
                request.totalPulses(),
                request.pulses().stream()
                        .map(pulse -> new PulseEntry(pulse.pulsedAt(), pulse.msSinceBoot(), pulse.deltaMs()))
                        .toList()
        );
    }

    public PulseResponse toResponse(PulseDocument document) {
        return new PulseResponse(
                document.getId().toHexString(),
                document.getDeviceId(),
                document.getSentAt(),
                document.getWindowMinutes(),
                document.getTotalPulses(),
                document.getPulses().stream()
                        .map(pulse -> new PulseResponse.PulseItemResponse(
                                pulse.getPulsedAt(), pulse.getMsSinceBoot(), pulse.getDeltaMs()))
                        .toList()
        );
    }
}
