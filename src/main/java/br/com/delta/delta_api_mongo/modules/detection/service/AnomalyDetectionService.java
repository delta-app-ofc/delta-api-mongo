package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Porta de entrada do detector para a ingestão. Garantias:
 * - nunca lança exceção: uma falha vira anomaly_detected = null + detection_error na janela;
 * - com delta.detection.enabled=false não faz nada;
 * - roda em uma única thread, fora da requisição: o POST do hardware responde sem esperar o detector e as
 *   janelas são avaliadas na ordem em que chegaram.
 */
@Service
public class AnomalyDetectionService {
    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);
    public static final String DETECTOR_VERSION = "1";

    private final ObjectProvider<AnomalyDetector> detector;
    private final DetectionResultWriter writer;
    private final DetectionProperties properties;
    private final ThreadPoolExecutor executor;

    @Autowired
    public AnomalyDetectionService(ObjectProvider<AnomalyDetector> detector, DetectionResultWriter writer,
                                   DetectionProperties properties) {
        this(detector, writer, properties, 1000);
    }

    AnomalyDetectionService(ObjectProvider<AnomalyDetector> detector, DetectionResultWriter writer,
                            DetectionProperties properties, int queueCapacity) {
        this.detector = detector;
        this.writer = writer;
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
            var thread = new Thread(runnable, "anomaly-detector");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Agenda a avaliação. Sob sobrecarga, aguarda vaga em vez de perder a janela inserida. */
    public synchronized void process(ConsumptionSummaryDocument saved) {
        if (!properties.enabled()) return;
        Runnable task = () -> processNow(saved);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException exception) {
            if (executor.isShutdown()) {
                writeResult(saved, DetectionResult.notEvaluated("Detector em encerramento", Map.of()));
                return;
            }
            // O monitor impede que novos produtores ultrapassem esta janela enquanto ela espera vaga.
            // Uma interrupção do HTTP não pode descartar uma janela que já foi gravada no Mongo.
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        executor.getQueue().put(task);
                        break;
                    } catch (InterruptedException interruptedException) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        executor.shutdown();
    }

    /** Avalia várias janelas em ordem cronológica (mais antiga primeiro). */
    public void processAllNow(List<ConsumptionSummaryDocument> windows) {
        windows.stream().sorted(Comparator.comparing(ConsumptionSummaryDocument::getWindowFinishedAt))
                .forEach(this::processNow);
    }

    /** Avalia na thread atual; usado pela fila e pelos testes. */
    public void processNow(ConsumptionSummaryDocument saved) {
        var active = detector.getIfAvailable();
        if (!properties.enabled() || active == null) return;
        DetectionResult result;
        try {
            result = active.evaluate(saved);
        } catch (RuntimeException exception) {
            log.warn("Falha no detector (dispositivo {}): {}", saved.getDeviceId(), exception.toString());
            result = DetectionResult.notEvaluated("Falha no detector: " + exception.getClass().getSimpleName(),
                    Map.of("config_version", properties.configVersion()));
        }
        writeResult(saved, result);
    }

    private void writeResult(ConsumptionSummaryDocument saved, DetectionResult result) {
        try {
            writer.write(saved.getId(), result, DETECTOR_VERSION + "/" + properties.configVersion());
        } catch (RuntimeException exception) {
            log.error("Não foi possível gravar o resultado do detector (janela {}): {}", saved.getId(),
                    exception.toString());
        }
    }
}
