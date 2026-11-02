package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AnomalyDetectionServiceTest {
    @Test
    void savedPendingWindowsAreRecoveredByANewWorkerWithoutBlockingThePost() throws Exception {
        var doc = window(T0);
        service.prepare(doc);
        assertThat(doc.getDetectionStatus()).isEqualTo("pending");
        when(writer.pending(100)).thenReturn(List.of(doc), List.of());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(detector.evaluate(doc)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return DetectionResult.notEvaluated("test", Map.of());
        });
        var restarted = new AnomalyDetectionService(provider, writer, properties());
        try {
            restarted.start(); // nenhuma nova notificação: recupera o que já estava no banco
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 2000; i++) restarted.process(doc);
            });
            release.countDown();
            verify(writer, timeout(5000)).write(eq(doc.getId()), any(), any());
        } finally {
            release.countDown();
            restarted.close();
        }
    }

    @Test
    void failureToPersistTheOldestResultKeepsLaterPendingWindowsUntouched() {
        var first = window(T0);
        var second = window(T0.plusSeconds(300));
        when(writer.pending(100)).thenReturn(List.of(first, second));
        when(detector.evaluate(first)).thenReturn(DetectionResult.notEvaluated("test", Map.of()));
        org.mockito.Mockito.doThrow(new IllegalStateException("mongo indisponível"))
                .when(writer).write(eq(first.getId()), any(), any());
        service.drainPending();
        verify(detector, never()).evaluate(second);
    }

    @org.junit.jupiter.api.AfterEach
    void stopWorker() { service.close(); }

    private static final Instant T0 = Instant.parse("2026-07-14T18:00:00Z");
    private AnomalyDetector detector;
    private DetectionResultWriter writer;
    private ObjectProvider<AnomalyDetector> provider;
    private AnomalyDetectionService service;

    @BeforeEach
    void setUp() {
        detector = mock(AnomalyDetector.class);
        writer = mock(DetectionResultWriter.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(detector);
        service = new AnomalyDetectionService(provider, writer, properties());
    }

    private static ConsumptionSummaryDocument window(Instant start) {
        return new ConsumptionSummaryDocument(new ObjectId(), "DEV-1", start, start.plusSeconds(300), 4.0, 0.8);
    }

    @Test
    void writesTheDetectorResultOnTheWindowTogetherWithTheVersion() {
        var doc = window(T0);
        var result = new DetectionResult(true, List.of("continuous_flow"), Map.of("k", "v"), null);
        when(detector.evaluate(doc)).thenReturn(result);

        service.processNow(doc);

        verify(writer).write(doc.getId(), result, "1/test");
    }

    @Test
    void aDetectorFailureBecomesNullPlusAnErrorAndNeverThrows() {
        var doc = window(T0);
        when(detector.evaluate(doc)).thenThrow(new IllegalStateException("mongo caiu"));

        service.processNow(doc);

        ArgumentCaptor<DetectionResult> captor = ArgumentCaptor.forClass(DetectionResult.class);
        verify(writer).write(eq(doc.getId()), captor.capture(), any());
        assertThat(captor.getValue().anomalyDetected()).isNull();
        assertThat(captor.getValue().error()).contains("Falha no detector").contains("IllegalStateException");
    }

    @Test
    void aFailureWhileSavingTheResultIsSwallowed() {
        var doc = window(T0);
        when(detector.evaluate(doc)).thenReturn(DetectionResult.notEvaluated("x", Map.of()));
        org.mockito.Mockito.doThrow(new IllegalStateException("sem permissão")).when(writer).write(any(), any(), any());

        service.processNow(doc);

        verify(writer).write(any(), any(), any());
    }

    @Test
    void doesNothingWhenTheDetectorIsDisabled() {
        var disabled = new DetectionProperties(false, "1", properties().zone(), properties().window(),
                properties().baseline(), properties().zscore(), properties().continuousFlow(), properties().night(),
                properties().pageHinkley(), properties().regime(), properties().alert(), properties().cohort(),
                properties().owner());
        service = new AnomalyDetectionService(provider, writer, disabled);

        service.process(window(T0));
        service.processNow(window(T0));

        verify(detector, never()).evaluate(any());
        verify(writer, never()).write(any(), any(), any());
    }

    @Test
    void doesNothingWhenNoDetectorBeanExists() {
        when(provider.getIfAvailable()).thenReturn(null);

        service.processNow(window(T0));

        verify(writer, never()).write(any(), any(), any());
    }

    @Test
    void processReturnsRightAwayAndEvaluatesInTheBackground() {
        var doc = window(T0);
        when(detector.evaluate(doc)).thenReturn(DetectionResult.notEvaluated("x", Map.of()));

        when(writer.pending(100)).thenReturn(List.of(doc), List.of());
        service.process(doc);

        verify(writer, timeout(TimeUnit.SECONDS.toMillis(5))).write(eq(doc.getId()), any(), any());
    }

    @Test
    void batchWindowsAreEvaluatedInChronologicalOrder() {
        var late = window(T0.plusSeconds(600));
        var early = window(T0);
        var middle = window(T0.plusSeconds(300));
        when(detector.evaluate(any())).thenReturn(DetectionResult.notEvaluated("x", Map.of()));

        service.processAllNow(List.of(late, early, middle));

        var order = inOrder(detector);
        order.verify(detector).evaluate(early);
        order.verify(detector).evaluate(middle);
        order.verify(detector).evaluate(late);
    }
}
