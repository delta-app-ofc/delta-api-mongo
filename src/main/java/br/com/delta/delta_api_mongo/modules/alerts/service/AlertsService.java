package br.com.delta.delta_api_mongo.modules.alerts.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.LeakSummaryResponse;
import br.com.delta.delta_api_mongo.modules.alerts.mapper.AlertMapper;
import br.com.delta.delta_api_mongo.modules.detection.repository.AlertHistoryRepository;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Regras da API de alertas: leitura e dispensa pelo próprio usuário, resumo de vazamentos.
 */
@Service
@ConditionalOnProperty(prefix = "delta.alerts", name = "enabled", havingValue = "true")
public class AlertsService {
    static final Set<String> SEVERITIES = Set.of("low", "medium", "high");
    static final int LEAK_WINDOW_HOURS = 24;

    private final AlertHistoryRepository repository;
    private final AlertMapper mapper;
    private final Clock clock;

    public AlertsService(AlertHistoryRepository repository, AlertMapper mapper, Clock clock) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PagedModel<AlertResponse> list(Integer userId, String severity, boolean includeDismissed, Pageable pageable) {
        if (pageable.isUnpaged() || pageable.getPageSize() > 100) {
            throw new IllegalArgumentException("A consulta deve ser paginada, com até 100 itens por página.");
        }
        if (severity != null && !SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("severity deve ser high, medium ou low.");
        }
        var page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        var result = severity == null
                ? (includeDismissed ? repository.findAllByUser(userId, page) : repository.findVisibleByUser(userId, page))
                : (includeDismissed ? repository.findAllByUserAndSeverity(userId, severity, page)
                : repository.findVisibleByUserAndSeverity(userId, severity, page));
        return new PagedModel<>(result.map(mapper::toResponse));
    }

    public AlertResponse dismiss(Integer userId, String id) {
        if (!ObjectId.isValid(id)) {
            throw new IllegalArgumentException("O identificador do alerta deve ser um ObjectId válido.");
        }
        // Alerta de outro usuário é tratado como inexistente, para não revelar que existe.
        var alert = repository.findById(new ObjectId(id)).filter(found -> userId.equals(found.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Alerta não encontrado."));
        if (alert.getDismissedAt() == null) {
            alert.setDismissedAt(clock.instant());
            repository.save(alert);
        }
        return mapper.toResponse(alert);
    }

    public LeakSummaryResponse leakSummary(Integer userId) {
        Instant since = clock.instant().minus(Duration.ofHours(LEAK_WINDOW_HOURS));
        // Um ponto de vazamento = um dispositivo; vários alertas do mesmo dispositivo contam uma vez (maior estimativa).
        Map<String, Long> litersByPoint = new HashMap<>();
        for (var alert : repository.findLeakAlerts(userId, since)) {
            String point = alert.getDeviceId() != null ? alert.getDeviceId() : "alert:" + alert.getId();
            long liters = alert.getEvidence() != null && alert.getEvidence().get("liters_per_day_estimate") instanceof Number n
                    ? n.longValue() : 0L;
            litersByPoint.merge(point, liters, Math::max);
        }
        long total = litersByPoint.values().stream().mapToLong(Long::longValue).sum();
        return new LeakSummaryResponse(total, litersByPoint.size(), LEAK_WINDOW_HOURS, since);
    }
}
