package br.com.delta.delta_api_mongo.modules.device_status.service;

import br.com.delta.delta_api_mongo.common.devicevalidation.SqlDeviceValidationClient;
import br.com.delta.delta_api_mongo.common.exception.DeviceRejectedException;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.device_status.dto.request.DeviceStatusRequest;
import br.com.delta.delta_api_mongo.modules.device_status.dto.response.DeviceStatusResponse;
import br.com.delta.delta_api_mongo.modules.device_status.mapper.DeviceStatusMapper;
import br.com.delta.delta_api_mongo.modules.device_status.repository.DeviceStatusIndexes;
import br.com.delta.delta_api_mongo.modules.device_status.repository.DeviceStatusRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class DeviceStatusService {
    private final DeviceStatusRepository repository;
    private final DeviceStatusMapper mapper;
    private final SqlDeviceValidationClient deviceValidation;
    private final DeviceStatusIndexes indexes;

    public DeviceStatusResponse receive(@NotNull @Valid DeviceStatusRequest request) {
        String deviceId = deviceValidation.validate(request.deviceId()).orElseThrow(DeviceRejectedException::new);
        indexes.ensureCreated();
        return mapper.toResponse(repository.saveLatest(mapper.toDocument(request, deviceId)));
    }

    public DeviceStatusResponse findByDeviceId(@NotBlank @Size(max = 100) String deviceId) {
        return repository.findByDeviceId(deviceId).map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Status do dispositivo não encontrado."));
    }
}
