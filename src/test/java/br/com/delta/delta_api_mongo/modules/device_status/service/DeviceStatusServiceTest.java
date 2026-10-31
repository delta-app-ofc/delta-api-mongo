package br.com.delta.delta_api_mongo.modules.device_status.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.device_status.document.DeviceStatusDocument;
import br.com.delta.delta_api_mongo.modules.device_status.dto.request.DeviceStatusRequest;
import br.com.delta.delta_api_mongo.modules.device_status.mapper.DeviceStatusMapper;
import br.com.delta.delta_api_mongo.modules.device_status.repository.DeviceStatusIndexes;
import br.com.delta.delta_api_mongo.modules.device_status.repository.DeviceStatusRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceStatusServiceTest {
    private static final String DEVICE_ID = "ESP32-SP-0912";
    private static final Instant PING = Instant.parse("2026-07-17T17:10:00Z");

    private DeviceStatusRepository repository;
    private DeviceStatusIndexes indexes;
    private DeviceStatusService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeviceStatusRepository.class);
        indexes = mock(DeviceStatusIndexes.class);
        service = new DeviceStatusService(repository, new DeviceStatusMapper(), indexes);
    }

    @Test
    void receiveEnsuresIndexesAndMapsLatestStatus() {
        var latest = document();
        when(repository.saveLatest(any(DeviceStatusDocument.class))).thenReturn(latest);

        var result = service.receive(new DeviceStatusRequest(
                DEVICE_ID, PING, "-55 dBm", "v1.2.3", "online", 45, null));

        assertThat(result.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(result.lastPingAt()).isEqualTo(PING);
        assertThat(result.batteryLevel()).isEqualTo(45);
        verify(indexes).ensureCreated();
    }

    @Test
    void findByDeviceIdMapsExistingStatus() {
        var document = document();
        when(repository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.of(document));

        var result = service.findByDeviceId(DEVICE_ID);

        assertThat(result.id()).isEqualTo(document.getId().toHexString());
        assertThat(result.connectivityStatus()).isEqualTo("online");
    }

    @Test
    void findByDeviceIdRaisesNotFoundWhenNoStatusExists() {
        when(repository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByDeviceId(DEVICE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static DeviceStatusDocument document() {
        return new DeviceStatusDocument(
                new ObjectId(), DEVICE_ID, PING, "-55 dBm", "v1.2.3", "online", 45, null);
    }
}
