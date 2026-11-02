package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.repository.UserHourBaselineRepository;
import br.com.delta.delta_api_mongo.modules.detection.rules.RobustStats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Guarda e atualiza o "normal" de cada dispositivo.
 * - Baseline lento: mediana e MAD dos últimos litros aceitos no slot (semanas de histórico).
 * - Baseline rápido: média e variância móveis exponenciais (poucos dias), usado durante uma mudança de regime.
 */
@Service
@ConditionalOnProperty(prefix = "delta.detection", name = "enabled", havingValue = "true")
public class BaselineService {
    private final UserHourBaselineRepository repository;
    private final DetectionProperties properties;

    public BaselineService(UserHourBaselineRepository repository, DetectionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Hora da semana no fuso do imóvel: 0 = segunda 00h ... 167 = domingo 23h. */
    public static int hourOfWeek(Instant instant, ZoneId zone) {
        var local = instant.atZone(zone);
        return (local.getDayOfWeek().getValue() - 1) * 24 + local.getHour();
    }

    public UserHourBaselineDocument slot(String deviceId, int hourOfWeek) {
        return repository.findByDeviceIdAndHourOfWeek(deviceId, hourOfWeek)
                .orElseGet(() -> new UserHourBaselineDocument(deviceId, hourOfWeek));
    }

    public UserHourBaselineDocument deviceState(String deviceId) {
        return slot(deviceId, UserHourBaselineDocument.DEVICE_STATE_SLOT);
    }

    public void save(UserHourBaselineDocument document) {
        repository.save(document);
    }

    /** Aceita a janela no baseline lento: guarda a amostra (limitada) e recalcula mediana e MAD. */
    public void acceptSlow(UserHourBaselineDocument slot, double liters) {
        var samples = new ArrayList<>(slot.getSamples());
        samples.add(liters);
        int max = properties.baseline().maxSamples();
        while (samples.size() > max) samples.remove(0);
        slot.setSamples(samples);
        double median = RobustStats.median(samples);
        slot.setMedian(median);
        slot.setMad(RobustStats.mad(samples, median));
        slot.setCount(slot.getCount() + 1);
    }

    /** Atualiza o baseline rápido (média e variância móveis exponenciais). */
    public void updateFast(UserHourBaselineDocument slot, double liters) {
        double alpha = properties.baseline().ewmaAlpha();
        if (slot.getEwmaMean() == null) {
            slot.setEwmaMean(liters);
            slot.setEwmaVar(0.0);
            return;
        }
        double diff = liters - slot.getEwmaMean();
        slot.setEwmaMean(slot.getEwmaMean() + alpha * diff);
        slot.setEwmaVar((1 - alpha) * (slot.getEwmaVar() + alpha * diff * diff));
    }

    /** Média móvel lenta da vazão: piso noturno (janelas de madrugada) e vazão típica de uso. */
    public void updateFloors(UserHourBaselineDocument slot, double lpm, boolean quietPeriod) {
        double alpha = properties.baseline().floorAlpha();
        if (quietPeriod) {
            slot.setNightFloorLpm(slot.getNightFloorLpm() == null ? lpm
                    : slot.getNightFloorLpm() + alpha * (lpm - slot.getNightFloorLpm()));
        }
        if (lpm > 0) {
            slot.setMinFlowMedian(slot.getMinFlowMedian() == null ? lpm
                    : slot.getMinFlowMedian() + alpha * (lpm - slot.getMinFlowMedian()));
        }
    }

    public boolean hasSlowHistory(UserHourBaselineDocument slot) {
        return slot.getSamples().size() >= properties.baseline().minSamples() && slot.getMedian() != null;
    }

    /**
     * Um regime que durou promoteDays vira o novo normal: o baseline rápido passa a ser o lento
     * em todos os slots do dispositivo.
     */
    public void promoteFastToSlow(String deviceId) {
        List<UserHourBaselineDocument> slots = repository.findByDeviceIdAndHourOfWeekGreaterThanEqual(deviceId, 0);
        for (var slot : slots) {
            promoteSlot(slot);
        }
        repository.saveAll(slots);
    }

    /** Também aplica a promoção à instância já carregada da janela, que será salva no fim da avaliação. */
    public void promoteSlot(UserHourBaselineDocument slot) {
        if (slot.getEwmaMean() == null) return;
        double mean = slot.getEwmaMean();
        double sigma = Math.sqrt(Math.max(slot.getEwmaVar() == null ? 0 : slot.getEwmaVar(), 0));
        slot.setSamples(new ArrayList<>(List.of(mean)));
        slot.setMedian(mean);
        slot.setMad(sigma / RobustStats.MAD_TO_SIGMA);
    }
}
