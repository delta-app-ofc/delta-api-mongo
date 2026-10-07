package br.com.delta.delta_api_mongo.modules.pulse.service;

import br.com.delta.delta_api_mongo.common.deviceauth.AuthenticatedDevice;
import br.com.delta.delta_api_mongo.common.deviceauth.DeviceAuthenticationException;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.pulse.dto.request.PulseRequest;
import br.com.delta.delta_api_mongo.modules.pulse.dto.response.PulseResponse;
import br.com.delta.delta_api_mongo.modules.pulse.mapper.PulseMapper;
import br.com.delta.delta_api_mongo.modules.pulse.repository.PulseRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;

@Service
@Validated
@RequiredArgsConstructor
public class PulseService {

    private final PulseRepository repository;
    private final PulseMapper mapper;

    public PulseResponse create(@NotNull @Valid PulseRequest request, AuthenticatedDevice device) {
        if (device == null) {
            throw DeviceAuthenticationException.unauthorized();
        }
        if (!device.permissions().contains("telemetry:write")) {
            throw DeviceAuthenticationException.forbidden();
        }
        if (!device.deviceId().equals(request.deviceId())) {
            throw new IllegalArgumentException("device_id deve corresponder ao dispositivo autenticado.");
        }
        validateBatch(request);
        return mapper.toResponse(repository.save(mapper.toDocument(request, device.deviceId())));
    }

    public PulseResponse findById(@NotBlank String id) {
        if (!ObjectId.isValid(id)) {
            throw new IllegalArgumentException("O identificador do lote deve ser um ObjectId válido.");
        }

        return repository.findById(new ObjectId(id))
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Lote de pulsos não encontrado."));
    }

    public Page<PulseResponse> findByDeviceAndPeriod(
            @NotBlank String deviceId,
            @NotNull Instant start,
            @NotNull Instant end,
            @NotNull Pageable pageable
    ) {
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("O início do período deve ser anterior ao fim.");
        }
        if (pageable.isUnpaged()) {
            throw new IllegalArgumentException("A consulta de pulsos deve ser paginada.");
        }

        return repository.findByDeviceIdAndSentAtGreaterThanEqualAndSentAtLessThanOrderBySentAtDesc(
                deviceId, start, end, pageable
        ).map(mapper::toResponse);
    }

    private void validateBatch(PulseRequest request) {
        if (request.totalPulses() != request.pulses().size()) {
            throw new IllegalArgumentException("total_pulses deve corresponder à quantidade de itens em pulses.");
        }

        Instant windowStart = request.sentAt().minusSeconds(request.windowMinutes() * 60L);
        Instant previous = windowStart;
        for (var pulse : request.pulses()) {
            if (pulse.pulsedAt().isBefore(windowStart) || pulse.pulsedAt().isAfter(request.sentAt())) {
                throw new IllegalArgumentException("Os pulsos devem estar dentro da janela anterior a sent_at.");
            }
            if (pulse.pulsedAt().isBefore(previous)) {
                throw new IllegalArgumentException("Os pulsos devem estar em ordem cronológica.");
            }
            previous = pulse.pulsedAt();
        }
    }
}
