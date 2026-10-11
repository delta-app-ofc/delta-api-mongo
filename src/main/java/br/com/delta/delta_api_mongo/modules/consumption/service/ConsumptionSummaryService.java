package br.com.delta.delta_api_mongo.modules.consumption.service;

import br.com.delta.delta_api_mongo.common.devicevalidation.SqlDeviceValidationClient;
import br.com.delta.delta_api_mongo.common.exception.DeviceRejectedException;
import br.com.delta.delta_api_mongo.common.exception.ResourceAlreadyExistsException;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.consumption.dto.request.ConsumptionSummaryRequest;
import br.com.delta.delta_api_mongo.modules.consumption.dto.response.ConsumptionSummaryResponse;
import br.com.delta.delta_api_mongo.modules.consumption.mapper.ConsumptionSummaryMapper;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryIndexes;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import java.time.Instant;

@Service
@Validated
@RequiredArgsConstructor
public class ConsumptionSummaryService {
    private final ConsumptionSummaryRepository repository;
    private final ConsumptionSummaryMapper mapper;
    private final SqlDeviceValidationClient deviceValidation;
    private final ConsumptionSummaryIndexes indexes;

    public ConsumptionSummaryResponse create(@NotNull @Valid ConsumptionSummaryRequest request) {
        validatePeriod(request.windowStartedAt(), request.windowFinishedAt());
        if (!Double.isFinite(request.consumptionLiters())
                || (request.lpmAverage() != null && !Double.isFinite(request.lpmAverage()))) {
            throw new IllegalArgumentException("Consumo e vazão devem ser valores finitos.");
        }
        String deviceId = deviceValidation.validate(request.deviceId()).orElseThrow(DeviceRejectedException::new);
        indexes.ensureCreated();
        try {
            return mapper.toResponse(repository.insert(mapper.toDocument(request, deviceId)));
        } catch (DuplicateKeyException exception) {
            throw new ResourceAlreadyExistsException("Já existe um resumo para este dispositivo e esta janela.");
        }
    }

    public ConsumptionSummaryResponse findById(@NotBlank String id) {
        if (!ObjectId.isValid(id)) {
            throw new IllegalArgumentException("O identificador do resumo deve ser um ObjectId válido.");
        }
        return repository.findById(new ObjectId(id)).map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Resumo de consumo não encontrado."));
    }

    public Page<ConsumptionSummaryResponse> findByDeviceAndPeriod(
            @NotBlank @Size(max = 100) String deviceId, @NotNull Instant start, @NotNull Instant end,
            @NotNull Pageable pageable) {
        validatePeriod(start, end);
        if (pageable.isUnpaged() || pageable.getPageSize() > 100) {
            throw new IllegalArgumentException("A consulta deve ser paginada, com até 100 itens por página.");
        }
        return repository.findByDeviceIdAndWindowStartedAtGreaterThanEqualAndWindowStartedAtLessThanOrderByWindowStartedAtDescIdDesc(
                deviceId, start, end, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .map(mapper::toResponse);
    }

    private void validatePeriod(Instant start, Instant end) {
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("O início do período deve ser anterior ao fim.");
        }
    }
}
