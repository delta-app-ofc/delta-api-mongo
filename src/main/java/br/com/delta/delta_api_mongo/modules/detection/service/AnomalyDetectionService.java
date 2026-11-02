package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A janela entra com detection_status=pending no mesmo insert da ingestão. Um único worker busca pendências
 * no Mongo por ordem de término; a fila em memória contém somente um sinal de trabalho, nunca as janelas.
 * Um restart ou sobrecarga não apaga a pendência, e o POST não aguarda espaço nem a execução do detector.
 * Operação prevista com uma réplica do detector; não há transação distribuída entre os dois clusters.
 */
@Service
public class AnomalyDetectionService {
    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);
    public static final String DETECTOR_VERSION = "1";
    private static final int BATCH_SIZE = 100;
    private final ObjectProvider<AnomalyDetector> detector;
    private final DetectionResultWriter writer;
    private final DetectionProperties properties;
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "anomaly-detector");
        thread.setDaemon(true);
        return thread;
    });

    public AnomalyDetectionService(ObjectProvider<AnomalyDetector> detector, DetectionResultWriter writer,
                                   DetectionProperties properties) {
        this.detector = detector;
        this.writer = writer;
        this.properties = properties;
    }

    /** Marca a pendência antes do insert; sem uma segunda gravação no POST. */
    public void prepare(ConsumptionSummaryDocument document) {
        if (properties.enabled()) document.setDetectionStatus("pending");
    }

    /** Recupera pendências de uma execução anterior somente depois da aplicação e dos índices estarem prontos. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (properties.enabled() && started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::scanSafely, 0, 1, TimeUnit.SECONDS);
        }
    }

    /** Coalesce as notificações: milhares de POSTs geram no máximo um sinal aguardando execução. */
    public void process(ConsumptionSummaryDocument saved) {
        if (!properties.enabled() || !scanQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { scanSafely(); }
                finally { scanQueued.set(false); }
            });
        } catch (RejectedExecutionException exception) {
            scanQueued.set(false);
            // No encerramento a janela continua pending no banco e será retomada no próximo start.
            log.debug("Worker encerrando; a janela {} permanece pendente no MongoDB.", saved.getId());
        }
    }

    private void scanSafely() {
        try { drainPending(); }
        catch (RuntimeException exception) {
            log.warn("Falha ao ler pendências do detector: {}", exception.getClass().getSimpleName());
        }
    }

    /** Consulta limitada e ordenada; uma falha de escrita impede avançar para a próxima janela do lote. */
    void drainPending() {
        if (!properties.enabled() || detector.getIfAvailable() == null) return;
        for (var document : writer.pending(BATCH_SIZE)) {
            if (!processNow(document)) break;
        }
    }

    @PreDestroy
    public void close() { executor.shutdown(); }

    public void processAllNow(List<ConsumptionSummaryDocument> windows) {
        for (var document : windows.stream()
                .sorted(Comparator.comparing(ConsumptionSummaryDocument::getWindowFinishedAt)).toList()) {
            if (!processNow(document)) break;
        }
    }

    /** Um erro de avaliação é um resultado concluído com anomaly_detected=null; erro de escrita mantém pending. */
    public boolean processNow(ConsumptionSummaryDocument saved) {
        if (!properties.enabled()) return true;
        var active = detector.getIfAvailable();
        if (active == null) return true;
        DetectionResult result;
        try {
            result = active.evaluate(saved);
        } catch (RuntimeException exception) {
            log.warn("Falha no detector (dispositivo {}): {}", saved.getDeviceId(), exception.getClass().getSimpleName());
            result = DetectionResult.notEvaluated("Falha no detector: " + exception.getClass().getSimpleName(),
                    Map.of("config_version", properties.configVersion()));
        }
        try {
            writer.write(saved.getId(), result, DETECTOR_VERSION + "/" + properties.configVersion());
            return true;
        } catch (RuntimeException exception) {
            log.error("Resultado da janela {} não foi salvo; permanece pendente: {}", saved.getId(),
                    exception.getClass().getSimpleName());
            return false;
        }
    }
}
