package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

/**
 * Dono do dispositivo com cache. O dono quase nunca muda, então uma consulta por dispositivo a cada
 * cacheMinutes basta e evita chamar a delta-api-postgres a cada janela de 5 minutos.
 * Falha na consulta não derruba a detecção: o dono fica desconhecido (sem regra noturna/expediente e sem alerta).
 */
@Component
public class DeviceOwnerResolver {
    private static final Logger log = LoggerFactory.getLogger(DeviceOwnerResolver.class);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(1);
    static final int MAX_CACHE_ENTRIES = 1000;

    private record Entry(Optional<DeviceOwner> owner, Instant expiresAt) {
    }

    private final DeviceOwnerClient client;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> cache = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public DeviceOwnerResolver(DeviceOwnerClient client, DetectionProperties properties) {
        this(client, properties, Clock.systemUTC());
    }

    DeviceOwnerResolver(DeviceOwnerClient client, DetectionProperties properties, Clock clock) {
        this.client = client;
        this.ttl = Duration.ofMinutes(properties.owner().cacheMinutes());
        this.clock = clock;
    }

    public synchronized Optional<DeviceOwner> resolve(String deviceId) {
        Instant now = clock.instant();
        cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        Entry cached = cache.get(deviceId);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.owner();
        try {
            Optional<DeviceOwner> owner = client.find(deviceId);
            cache(deviceId, owner, now.plus(client.isConfigured() && owner.isPresent() ? ttl : FAILURE_TTL));
            return owner;
        } catch (RuntimeException exception) {
            log.warn("Falha ao consultar o dono do dispositivo {}: {}", deviceId, exception.getClass().getSimpleName());
            cache(deviceId, Optional.empty(), now.plus(FAILURE_TTL));
            return Optional.empty();
        }
    }

    /** Autorizacao de leitura/escrita exige cadastro atual, sem reutilizar vinculo em cache. */
    public Optional<DeviceOwner> fresh(String deviceId) {
        try {
            return client.find(deviceId);
        } catch (RuntimeException failure) {
            throw new br.com.delta.delta_api_mongo.common.exception.ServiceUnavailableException(
                    "Cadastro temporariamente indisponivel. Reenvie a requisicao.");
        }
    }

    private void cache(String deviceId, Optional<DeviceOwner> owner, Instant expiresAt) {
        if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(deviceId)) {
            cache.remove(cache.keySet().iterator().next());
        }
        cache.put(deviceId, new Entry(owner, expiresAt));
    }
}
