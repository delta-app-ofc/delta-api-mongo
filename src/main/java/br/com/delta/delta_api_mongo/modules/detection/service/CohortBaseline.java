package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.repository.UserHourBaselineRepository;
import br.com.delta.delta_api_mongo.modules.detection.rules.RobustStats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cold start: enquanto um dispositivo novo não tem histórico no slot, usa como referência a mediana dos
 * outros dispositivos de mesma classificação, tipo de imóvel e região na mesma hora da semana.
 * A chave vem do cadastro SQL; ausência de perfil não autoriza fallback global.
 */
@Service
@ConditionalOnProperty(prefix = "delta.detection", name = "enabled", havingValue = "true")
public class CohortBaseline {
    public record Reference(double median, double mad, int devices) {
    }

    private record Entry(Optional<Reference> reference, Instant expiresAt) {
    }

    private final UserHourBaselineRepository repository;
    private final DetectionProperties.Cohort config;
    private final Clock clock;
    private final Map<String, Entry> cache = new java.util.LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CohortBaseline(UserHourBaselineRepository repository, DetectionProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    CohortBaseline(UserHourBaselineRepository repository, DetectionProperties properties, Clock clock) {
        this.repository = repository;
        this.config = properties.cohort();
        this.clock = clock;
    }

    /** O dispositivo consultado ainda não tem histórico no slot, então não entra na própria referência. */
    public Optional<Reference> reference(int hourOfWeek) { return Optional.empty(); }

    /** Sem perfil comparável, não mistura residências com empresas ou imóveis de outra região. */
    public synchronized Optional<Reference> reference(int hourOfWeek, String cohortKey, String deviceId) {
        if (cohortKey == null || cohortKey.isBlank()) return Optional.empty();
        String key = cohortKey + ":" + hourOfWeek + ":" + deviceId;
        if (!config.enabled()) return Optional.empty();
        Instant now = clock.instant();
        cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        Entry cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.reference();

        List<UserHourBaselineDocument> others = repository
                .findByCohortKeyAndHourOfWeekAndCountGreaterThanEqualAndDeviceIdNot(
                        cohortKey, hourOfWeek, config.minCount(), deviceId, PageRequest.of(0, 200))
                .stream().filter(slot -> slot.getMedian() != null && slot.getMad() != null).toList();
        Optional<Reference> result = others.size() < config.minDevices() ? Optional.empty()
                : Optional.of(new Reference(
                        RobustStats.median(others.stream().map(UserHourBaselineDocument::getMedian).toList()),
                        RobustStats.median(others.stream().map(UserHourBaselineDocument::getMad).toList()),
                        others.size()));
        if (cache.size() >= 1000) cache.remove(cache.keySet().iterator().next());
        cache.put(key, new Entry(result, now.plus(Duration.ofMinutes(config.cacheMinutes()))));
        return result;
    }
}
