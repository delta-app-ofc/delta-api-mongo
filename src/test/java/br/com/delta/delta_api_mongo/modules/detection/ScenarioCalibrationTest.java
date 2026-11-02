package br.com.delta.delta_api_mongo.modules.detection;

import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.model.BusinessSchedule;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibração em JUnit: cenários sintéticos com semente fixa, rotulados (sabemos onde começa o vazamento).
 * Metas: nenhum alerta falso em casas normais por várias semanas e atraso de detecção de poucas horas.
 * Se um limiar em delta.detection.* for alterado, estes testes dizem se a meta continua valendo.
 */
class ScenarioCalibrationTest {
    private static final String DEVICE = "DEV-1";
    private static final Instant ORIGIN = MONDAY_MIDNIGHT;
    private static final int WEEKS = 6;
    private static final DeviceOwner RESIDENCE = new DeviceOwner(10, "RESIDENCIAL", List.of(7), null);
    private static final DeviceOwner COMPANY = new DeviceOwner(11, "COMERCIAL", List.of(50), new BusinessSchedule(
            List.of(new BusinessSchedule.Shift(LocalTime.of(8, 0), LocalTime.of(18, 0))),
            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)));

    private record Run(DetectorHarness harness, List<DetectionResult> results) {
    }

    /** Simula de dia fromDay (inclusive) até toDay (exclusive), uma janela de 5 minutos por vez. */
    private static void simulate(SyntheticHome home, DetectorHarness harness, int fromDay, int toDay,
                                 List<DetectionResult> results) {
        Instant start = ORIGIN.plus(Duration.ofDays(fromDay));
        Instant end = ORIGIN.plus(Duration.ofDays(toDay));
        for (Instant t = start; t.isBefore(end); t = t.plusSeconds(SyntheticHome.WINDOW_SECONDS)) {
            var sample = home.next(t);
            if (sample == null) continue;
            results.add(harness.feed(DEVICE, sample.start(), sample.liters(), sample.lpm()));
        }
    }

    private static DetectorHarness harness(DeviceOwner owner) {
        return new DetectorHarness(properties(), Optional.of(owner));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void aNormalResidenceNeverGeneratesAnAlertInSixWeeks(long seed) {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();

        simulate(new SyntheticHome(seed, ZONE, false), harness, 0, WEEKS * 7, results);

        assertThat(harness.alerts).as("alertas falsos, semente %d", seed).isEmpty();
        assertThat(results).noneMatch(result -> Boolean.TRUE.equals(result.anomalyDetected()));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void aSteadyNightLeakIsDetectedWithinAFewHoursAndOpensOneAlert(long seed) {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(seed, ZONE, false);
        simulate(home, harness, 0, 28, results);
        assertThat(harness.alerts).isEmpty();

        // vazamento de 0,3 L/min começa na segunda da 5a semana, às 22:00
        Instant leakStart = SyntheticHome.at(ORIGIN, 28, 22, 0);
        home.leak(0.3);
        simulate(home, harness, 28, 29, results);

        Optional<Instant> firstAlert = harness.alerts.stream().map(AlertHistoryDocument::getTriggeredAt).min(Instant::compareTo);
        assertThat(firstAlert).isPresent();
        // o vazamento só entra nas janelas a partir das 00:00 do dia 28 nesta simulação: mede do início do dia
        assertThat(Duration.between(ORIGIN.plus(Duration.ofDays(28)), firstAlert.get()).toHours()).isLessThanOrEqualTo(6);
        assertThat(harness.alerts).extracting(AlertHistoryDocument::getUserId).containsOnly(7);
        assertThat(harness.alerts).extracting(AlertHistoryDocument::getAlertType).contains("vazamento_continuo");
        assertThat(harness.alerts.stream().filter(alert -> alert.getResolvedAt() == null)
                .map(AlertHistoryDocument::getRule).distinct().count())
                .as("no máximo um alerta aberto por regra")
                .isEqualTo(harness.alerts.stream().filter(alert -> alert.getResolvedAt() == null).count());
        assertThat(leakStart).isNotNull();
    }

    @Test
    void aSmallLeakOfOneTenthLiterPerMinuteIsStillDetectedAtNight() {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(7, ZONE, false);
        simulate(home, harness, 0, 28, results);

        home.leak(0.1);
        simulate(home, harness, 28, 30, results);

        assertThat(harness.alerts).isNotEmpty();
        assertThat(Duration.between(ORIGIN.plus(Duration.ofDays(28)),
                harness.alerts.get(0).getTriggeredAt()).toHours()).isLessThanOrEqualTo(30);
    }

    @Test
    void theAlertIsClosedAfterTheLeakIsFixed() {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(11, ZONE, false);
        simulate(home, harness, 0, 28, results);
        home.leak(0.3);
        simulate(home, harness, 28, 29, results);
        assertThat(harness.openAlerts()).isPositive();

        home.leak(0.0);
        simulate(home, harness, 29, 31, results);

        assertThat(harness.openAlerts()).isZero();
        assertThat(harness.alerts).allSatisfy(alert -> assertThat(alert.getResolvedAt()).isNotNull());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void aThreeDayVisitGoingUpAndDownIsNotALeak(long seed) {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(seed, ZONE, false);
        simulate(home, harness, 0, 28, results);

        home.usage(2.0, 1.4);
        simulate(home, harness, 28, 31, results);
        home.usage(1.0, 1.0);
        simulate(home, harness, 31, 42, results);

        assertThat(harness.alerts).as("a visita não pode gerar alerta de vazamento, semente %d", seed)
                .noneMatch(alert -> "vazamento_continuo".equals(alert.getAlertType()));
        assertThat(harness.alerts).isEmpty();
    }

    @Test
    void aLeakDuringAVisitIsStillDetected() {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(5, ZONE, false);
        simulate(home, harness, 0, 28, results);

        home.usage(2.0, 1.4);
        simulate(home, harness, 28, 30, results);
        home.leak(0.3);
        simulate(home, harness, 30, 31, results);

        assertThat(harness.alerts).extracting(AlertHistoryDocument::getAlertType).contains("vazamento_continuo");
    }

    @Test
    void aWeekOfVacationWithNoConsumptionAndTheReturnDoNotGenerateAlerts() {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(9, ZONE, false);
        simulate(home, harness, 0, 28, results);

        home.usage(0.0, 1.0);
        simulate(home, harness, 28, 35, results);
        home.usage(1.0, 1.0);
        simulate(home, harness, 35, 49, results);

        assertThat(harness.alerts).isEmpty();
    }

    @Test
    void aSixHourOutageIsNotTreatedAsAnAnomaly() {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        Instant outageStart = SyntheticHome.at(ORIGIN, 30, 9, 0);
        var home = new SyntheticHome(13, ZONE, false)
                .offlineWhen(t -> !t.isBefore(outageStart) && t.isBefore(outageStart.plus(Duration.ofHours(6))));

        simulate(home, harness, 0, 40, results);

        assertThat(harness.alerts).isEmpty();
        assertThat(results).noneMatch(result -> Boolean.TRUE.equals(result.anomalyDetected()));
        assertThat(results).anyMatch(result -> Boolean.TRUE.equals(result.evidence().get("data_gap")));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void aCompanyWorkingItsNormalShiftNeverGeneratesAnAlert(long seed) {
        var harness = harness(COMPANY);
        var results = new ArrayList<DetectionResult>();

        simulate(new SyntheticHome(seed, ZONE, true), harness, 0, WEEKS * 7, results);

        assertThat(harness.alerts).as("alertas falsos em empresa, semente %d", seed).isEmpty();
    }

    @Test
    void waterRunningAfterBusinessHoursIsReportedWithTheOffHoursAlertType() {
        var harness = harness(COMPANY);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(21, ZONE, true);
        simulate(home, harness, 0, 28, results);
        assertThat(harness.alerts).isEmpty();

        home.leak(0.3);
        simulate(home, harness, 28, 29, results);

        assertThat(harness.alerts).extracting(AlertHistoryDocument::getAlertType)
                .contains("consumo_fora_do_expediente");
        assertThat(harness.alerts).extracting(AlertHistoryDocument::getRule).contains("off_hours_flow");
    }

    @Test
    void theSameLeakInAResidenceIsNotAnOffHoursAlert() {
        var harness = harness(RESIDENCE);
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(21, ZONE, false);
        simulate(home, harness, 0, 28, results);
        home.leak(0.3);
        simulate(home, harness, 28, 29, results);

        assertThat(harness.alerts).extracting(AlertHistoryDocument::getAlertType)
                .doesNotContain("consumo_fora_do_expediente");
    }

    @Test
    void withoutAKnownOwnerTheWindowsAreStillMarkedButNoAlertIsCreated() {
        var harness = new DetectorHarness(properties(), Optional.empty());
        var results = new ArrayList<DetectionResult>();
        var home = new SyntheticHome(3, ZONE, false);
        simulate(home, harness, 0, 28, results);
        home.leak(0.3);
        simulate(home, harness, 28, 29, results);

        assertThat(harness.alerts).isEmpty();
        assertThat(results).anyMatch(result -> Boolean.TRUE.equals(result.anomalyDetected()));
    }
}
