package br.com.delta.delta_api_mongo.modules.alerts.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceAlreadyExistsException;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.alerts.dto.request.CreateAlertRequest;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.LeakSummaryResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.ResolveAlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.mapper.AlertMapper;
import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.repository.AlertHistoryRepository;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Regras da API de alertas: leitura e dispensa pelo próprio usuário, resumo de vazamentos e escrita da IA/sistema.
 * A escrita fica num lugar só (aqui) para validar e deduplicar do mesmo jeito sempre.
 */
@Service
@ConditionalOnProperty(prefix = "delta.alerts", name = "enabled", havingValue = "true")
public class AlertsService {
    static final Set<String> SEVERITIES = Set.of("low", "medium", "high");
    static final Set<String> GLUH_TYPES = Set.of("consumo_acima_esperado", "previsao_ultrapassagem_meta",
            "limite_ultrapassado", "previsao_conta_disponivel", "tarifa_atualizada", "dica_personalizada");
    static final Set<String> SYSTEM_TYPES = Set.of("tarifa_atualizada");
    /** Único tipo da IA que pode ser Crítico (severity high): a meta foi realmente ultrapassada. */
    static final String CRITICAL_GLUH_TYPE = "limite_ultrapassado";
    static final int LEAK_WINDOW_HOURS = 24;

    /** Resultado de criar: created = false quando já existia um alerta aberto com a mesma dedup_key. */
    public record CreateResult(AlertResponse alert, boolean created) {
    }

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

    public CreateResult create(CreateAlertRequest request) {
        validate(request);
        var existing = repository.findByDedupKeyAndResolvedAtIsNull(request.dedupKey());
        if (!existing.isEmpty()) return existingResult(existing.get(0), request);

        var alert = new AlertHistoryDocument();
        alert.setUserId(request.userId());
        alert.setPropertyId(request.propertyId());
        alert.setDeviceId(request.deviceId());
        alert.setAlertType(request.alertType());
        alert.setSeverity(request.severity());
        alert.setTitle(request.title());
        alert.setMessage(request.message());
        alert.setSource(request.source());
        alert.setDedupKey(request.dedupKey());
        alert.setEvidence(request.evidence() == null ? null : new LinkedHashMap<>(request.evidence()));
        alert.setTriggeredAt(request.triggeredAt() != null ? request.triggeredAt() : clock.instant());
        try {
            return new CreateResult(mapper.toResponse(repository.insert(alert)), true);
        } catch (DuplicateKeyException exception) {
            // Duas chamadas ao mesmo tempo: o índice único parcial deixou passar só uma; devolve a que ficou.
            var winner = repository.findByDedupKeyAndResolvedAtIsNull(request.dedupKey());
            if (winner.isEmpty()) throw exception;
            return existingResult(winner.get(0), request);
        }
    }

    public ResolveAlertResponse resolve(String dedupKey) {
        if (dedupKey == null || !(dedupKey.startsWith("gluh:") || dedupKey.startsWith("system:"))) {
            throw new IllegalArgumentException("O endpoint interno encerra somente alertas gluh ou system.");
        }
        var open = repository.findByDedupKeyAndResolvedAtIsNull(dedupKey);
        if (open.isEmpty()) return new ResolveAlertResponse(0);
        Instant now = clock.instant();
        open.forEach(alert -> alert.setResolvedAt(now));
        repository.saveAll(open);
        return new ResolveAlertResponse(open.size());
    }

    private CreateResult existingResult(AlertHistoryDocument existing, CreateAlertRequest request) {
        if (!request.userId().equals(existing.getUserId())) {
            throw new ResourceAlreadyExistsException("Já existe um alerta aberto com esta dedup_key para outro usuário.");
        }
        return new CreateResult(mapper.toResponse(existing), false);
    }

    private void validate(CreateAlertRequest request) {
        String source = request.source();
        boolean gluh = "gluh".equals(source);
        if (!gluh && !"system".equals(source)) {
            throw new IllegalArgumentException("source deve ser gluh ou system.");
        }
        if (!(gluh ? GLUH_TYPES : SYSTEM_TYPES).contains(request.alertType())) {
            throw new IllegalArgumentException("alert_type não permitido para o source " + source + ".");
        }
        if (!SEVERITIES.contains(request.severity())) {
            throw new IllegalArgumentException("severity deve ser high, medium ou low.");
        }
        if ("high".equals(request.severity()) && !(gluh && CRITICAL_GLUH_TYPE.equals(request.alertType()))) {
            throw new IllegalArgumentException("severity high só é permitido para " + CRITICAL_GLUH_TYPE
                    + " enviado pelo gluh.");
        }
        String requiredPrefix = gluh ? "gluh:" + request.userId() + ":" : "system:";
        if (!request.dedupKey().startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("dedup_key deve começar com \"" + requiredPrefix + "\".");
        }
        if (request.evidence() != null) validateKeys(request.evidence(), 0);
    }

    // Chaves começando com $ ou com ponto podem ser lidas como operadores/caminhos pelo MongoDB.
    private void validateKeys(Map<?, ?> map, int depth) {
        if (depth > 5) throw new IllegalArgumentException("evidence tem aninhamento demais.");
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("$") || key.contains(".")) {
                throw new IllegalArgumentException("evidence contém uma chave inválida: " + key);
            }
            if (entry.getValue() instanceof Map<?, ?> nested) validateKeys(nested, depth + 1);
        }
    }
}
