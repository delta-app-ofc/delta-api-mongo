package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.BusinessSchedule;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Consulta a delta-api-postgres para saber de quem é o dispositivo (imóvel, usuários, residencial ou
 * comercial e expediente). Usa a chave de serviço no cabeçalho X-Service-Key.
 */
@Component
public class DeviceOwnerClient {
    static final String HEADER = "X-Service-Key";

    private final DetectionProperties.Owner config;
    private final RestClient restClient;

    public DeviceOwnerClient(DetectionProperties properties) {
        this.config = properties.owner();
        if (isConfigured()) {
            var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(config.timeoutMs())).build());
            factory.setReadTimeout(Duration.ofMillis(config.timeoutMs()));
            this.restClient = RestClient.builder().baseUrl(config.url()).requestFactory(factory).build();
        } else {
            this.restClient = null;
        }
    }

    public boolean isConfigured() {
        return config.url() != null && !config.url().isBlank()
                && config.serviceKey() != null && !config.serviceKey().isBlank();
    }

    /** Vazio quando o dispositivo não existe (404). Lança exceção em falha de rede ou erro do servidor. */
    public Optional<DeviceOwner> find(String deviceId) {
        if (!isConfigured()) return Optional.empty();
        try {
            var body = restClient.get().uri("/delta/internal/device/{id}/owner", deviceId)
                    .header(HEADER, config.serviceKey()).retrieve().body(OwnerPayload.class);
            return Optional.ofNullable(body).map(OwnerPayload::toOwner);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OwnerPayload(Integer propertyId, String classificationGroup, List<Integer> userIds,
                        List<ShiftPayload> shifts, List<String> operationDays, String cohortKey) {
        OwnerPayload(Integer propertyId, String classificationGroup, List<Integer> userIds,
                     List<ShiftPayload> shifts, List<String> operationDays) {
            this(propertyId, classificationGroup, userIds, shifts, operationDays, null);
        }

        DeviceOwner toOwner() {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            if (operationDays != null) operationDays.forEach(day -> days.add(DayOfWeek.valueOf(day)));
            var parsed = shifts == null ? List.<BusinessSchedule.Shift>of() : shifts.stream()
                    .map(shift -> new BusinessSchedule.Shift(LocalTime.parse(shift.startTime()),
                            LocalTime.parse(shift.endTime()))).toList();
            return new DeviceOwner(propertyId, classificationGroup,
                    userIds == null ? List.of() : userIds, new BusinessSchedule(parsed, days), cohortKey);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShiftPayload(String startTime, String endTime) {
    }
}
