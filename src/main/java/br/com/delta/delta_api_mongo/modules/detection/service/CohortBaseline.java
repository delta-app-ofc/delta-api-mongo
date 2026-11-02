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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cold start: enquanto um dispositivo novo não tem histórico no slot, usa como referência a mediana dos
 * outros dispositivos na mesma hora da semana. Hoje a coorte é "todos os dispositivos com histórico";
 * quando houver atributos do imóvel (moradores, tipo) dá para filtrar por imóveis semelhantes.
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
    private final Map<Integer, Entry> cache = new ConcurrentHashMap<>();

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
    public Optional<Reference> reference(int hourOfWeek) {
        if (!config.enabled()) return Optional.empty();
        Instant now = clock.instant();
        Entry cached = cache.get(hourOfWeek);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.reference();

        List<UserHourBaselineDocument> others = repository
                .findByHourOfWeekAndCountGreaterThanEqual(hourOfWeek, config.minCount(), PageRequest.of(0, 200))
                .stream().filter(slot -> slot.getMedian() != null && slot.getMad() != null).toList();
        Optional<Reference> result = others.size() < config.minDevices() ? Optional.empty()
                : Optional.of(new Reference(
                        RobustStats.median(others.stream().map(UserHourBaselineDocument::getMedian).toList()),
                        RobustStats.median(others.stream().map(UserHourBaselineDocument::getMad).toList()),
                        others.size()));
        cache.put(hourOfWeek, new Entry(result, now.plus(Duration.ofMinutes(config.cacheMinutes()))));
        return result;
    }
}
