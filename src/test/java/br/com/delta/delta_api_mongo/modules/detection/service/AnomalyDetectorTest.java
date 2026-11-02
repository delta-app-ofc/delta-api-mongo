package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.detection.DetectorHarness;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {
    @Test
    void promotionSurvivesSavingTheAlreadyLoadedSlotWithDetachedRepositoryDocuments() {
        var repository = org.mockito.Mockito.mock(br.com.delta.delta_api_mongo.modules.detection.repository.UserHourBaselineRepository.class);
        var service = new BaselineService(repository, properties());
        int hour = BaselineService.hourOfWeek(T0, properties().zone());
        var loaded = new br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument(DEVICE, hour);
        for (int i = 0; i < 4; i++) service.acceptSlow(loaded, 5);
        loaded.setEwmaMean(20.0);
        loaded.setEwmaVar(9.0);
        var detached = new br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument(DEVICE, hour);
        detached.setEwmaMean(20.0);
        detached.setEwmaVar(9.0);
        org.mockito.Mockito.when(repository.findByDeviceIdAndHourOfWeek(DEVICE, hour)).thenReturn(Optional.of(loaded));
        org.mockito.Mockito.when(repository.findByDeviceIdAndHourOfWeek(DEVICE, -1)).thenReturn(Optional.empty());
        org.mockito.Mockito.when(repository.findByDeviceIdAndHourOfWeekGreaterThanEqual(DEVICE, 0))
                .thenReturn(List.of(detached));
        var consumption = org.mockito.Mockito.mock(br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository.class);
        var owners = org.mockito.Mockito.mock(DeviceOwnerResolver.class);
        org.mockito.Mockito.when(owners.resolve(DEVICE)).thenReturn(Optional.empty());
        var detector = new AnomalyDetector(properties(), consumption, service,
                org.mockito.Mockito.mock(CohortBaseline.class), owners, org.mockito.Mockito.mock(AlertService.class));
        var tracker = org.mockito.Mockito.mock(RegimeTracker.class);
        org.mockito.Mockito.when(tracker.update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new RegimeTracker.Outcome(false, false, true));
        org.springframework.test.util.ReflectionTestUtils.setField(detector, "regimeTracker", tracker);
        var doc = new ConsumptionSummaryDocument(new ObjectId(), DEVICE, T0, T0.plusSeconds(300), 20.0, 4.0);
        detector.evaluate(doc);
        assertThat(loaded.getMedian()).isEqualTo(20.0);
        assertThat(loaded.getSamples()).containsOnly(20.0);
        org.mockito.Mockito.verify(repository).save(loaded);
    }
    private static final String DEVICE = "DEV-1";
    private static final DeviceOwner RESIDENCE = new DeviceOwner(10, "RESIDENCIAL", List.of(7), null);
    // terça-feira 2026-07-14 15:00 local, longe da madrugada
    private static final Instant T0 = MONDAY_MIDNIGHT.plusSeconds((24 + 15) * 3600L);
    private DetectorHarness harness;

    @BeforeEach
    void setUp() {
        harness = new DetectorHarness(properties(), Optional.of(RESIDENCE));
    }

    /** Alimenta o mesmo slot (mesma hora da semana) por várias semanas para formar histórico. */
    private void buildHistory(double liters, int weeks) {
        for (int week = 0; week < weeks; week++) {
            Instant start = T0.minusSeconds(7L * 24 * 3600 * (weeks - week));
            harness.feed(DEVICE, start, liters, liters / 5.0);
        }
    }

    @Test
    void aNormalWindowIsEvaluatedAsNotAnomalous() {
        var result = harness.feed(DEVICE, T0, 4.0, 0.8);

        assertThat(result.anomalyDetected()).isFalse();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.error()).isNull();
        assertThat(result.evidence()).containsEntry("config_version", "test").containsKey("hour_of_week");
    }

    @Test
    void aWindowOlderThanTheLastProcessedOneIsNotEvaluatedAndLeavesTheBaselineAlone() {
        harness.feed(DEVICE, T0, 4.0, 0.8);
        int samplesBefore = harness.baselines.values().stream().mapToInt(doc -> doc.getSamples().size()).sum();

        var result = harness.feed(DEVICE, T0.minusSeconds(3600), 9.0, 1.8);

        assertThat(result.anomalyDetected()).isNull();
        assertThat(result.error()).contains("anterior ou igual");
        assertThat(harness.baselines.values().stream().mapToInt(doc -> doc.getSamples().size()).sum())
                .isEqualTo(samplesBefore);
    }

    @Test
    void theSameWindowProcessedTwiceDoesNotUpdateTheBaselineTwice() {
        harness.feed(DEVICE, T0, 4.0, 0.8);
        int samplesAfterFirst = harness.baselines.values().stream().mapToInt(doc -> doc.getSamples().size()).sum();

        var second = harness.detector.evaluate(harness.windows.get(0));

        assertThat(second.anomalyDetected()).isNull();
        assertThat(harness.baselines.values().stream().mapToInt(doc -> doc.getSamples().size()).sum())
                .isEqualTo(samplesAfterFirst);
    }

    @Test
    void aWindowWithTheWrongDurationIsNotEvaluated() {
        var doc = new ConsumptionSummaryDocument(new ObjectId(), DEVICE, T0, T0.plusSeconds(900), 4.0, 0.3);
        harness.windows.add(doc);

        var result = harness.detector.evaluate(doc);

        assertThat(result.anomalyDetected()).isNull();
        assertThat(result.error()).contains("fora do padrão");
        assertThat(harness.baselines).isEmpty();
    }

    @Test
    void aLongSilenceIsRecordedAsADataGapAndNotAsAnAnomaly() {
        harness.feed(DEVICE, T0, 4.0, 0.8);

        var result = harness.feed(DEVICE, T0.plusSeconds(6 * 3600), 4.0, 0.8);

        assertThat(result.anomalyDetected()).isFalse();
        assertThat(result.evidence()).containsEntry("data_gap", true);
    }

    @Test
    void aSingleHugeWindowIsOnlyACandidateAndNeedsConfirmationToBecomeAnomalous() {
        buildHistory(1.0, 6);
        Instant t = T0;

        var first = harness.feed(DEVICE, t, 80.0, 16.0);
        assertThat(first.anomalyDetected()).isFalse();
        assertThat(first.evidence().get("z")).isNotNull();

        // as janelas seguintes caem em outras horas; confirma pela contagem de janelas seguidas
        for (int i = 1; i < 3; i++) {
            assertThat(harness.feed(DEVICE, t.plusSeconds(300L * i), 80.0, 16.0).reasons()).doesNotContain("robust_zscore");
        }
    }

    @Test
    void windowsWithHighZAreKeptOutOfTheBaseline() {
        buildHistory(1.0, 6);
        int hour = BaselineServiceHour.of(T0);
        int samplesBefore = harness.baselines.get(DEVICE + "#" + hour).getSamples().size();

        harness.feed(DEVICE, T0, 80.0, 16.0);

        assertThat(harness.baselines.get(DEVICE + "#" + hour).getSamples()).hasSize(samplesBefore);
    }

    @Test
    void normalWindowsFeedTheSlowBaselineAndMedianFollowsThem() {
        buildHistory(2.0, 5);
        int hour = BaselineServiceHour.of(T0);

        var slot = harness.baselines.get(DEVICE + "#" + hour);

        assertThat(slot.getSamples()).hasSize(5);
        assertThat(slot.getMedian()).isEqualTo(2.0);
        assertThat(slot.getMad()).isZero();
    }

    @Test
    void theStateDocumentRecordsTheLastWindowAndConfigVersion() {
        harness.feed(DEVICE, T0, 4.0, 0.8);

        var state = harness.baselines.get(DEVICE + "#-1");

        assertThat(state.getLastWindowFinishedAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(state.getConfigVersion()).isEqualTo("test");
        assertThat(state.getRegimeState()).isEqualTo("NORMAL");
    }

    @Test
    void sixConsecutiveSteadyWindowsMakeAnAnomalyEvenWithoutKnowingTheOwner() {
        harness.setOwner(Optional.empty());
        DetectionResultHolder last = new DetectionResultHolder();
        for (int i = 0; i < 6; i++) last.value = harness.feed(DEVICE, T0.plusSeconds(300L * i), 1.0, 0.2);

        assertThat(last.value.anomalyDetected()).isTrue();
        assertThat(last.value.reasons()).containsExactly("continuous_flow");
        assertThat(harness.alerts).isEmpty();
    }

    private static class DetectionResultHolder {
        br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult value;
    }

    /** Mesmo cálculo de hora da semana usado pelo detector. */
    private static final class BaselineServiceHour {
        static int of(Instant instant) {
            return BaselineService.hourOfWeek(instant, ZONE);
        }
    }
}
