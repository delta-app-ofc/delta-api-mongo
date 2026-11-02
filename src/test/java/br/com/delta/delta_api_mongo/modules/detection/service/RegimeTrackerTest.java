package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.properties;
import static org.assertj.core.api.Assertions.assertThat;

class RegimeTrackerTest {
    private static final Instant T0 = Instant.parse("2026-07-13T12:00:00Z");
    private final RegimeTracker tracker = new RegimeTracker(properties());
    private final UserHourBaselineDocument state = new UserHourBaselineDocument("DEV-1", -1);

    /** Aplica o mesmo resíduo por n janelas de 5 minutos a partir de start; devolve o instante seguinte. */
    private Instant feed(Instant start, int windows, double residual) {
        Instant now = start;
        for (int i = 0; i < windows; i++) {
            now = now.plusSeconds(300);
            tracker.update(state, residual, now, false);
        }
        return now;
    }

    @Test
    void staysNormalWhileTheResidualsAreSmall() {
        feed(T0, 500, 0.2);

        assertThat(state.getRegimeState()).isEqualTo("NORMAL");
        assertThat(state.getCusumPos()).isZero();
    }

    @Test
    void aSustainedStepMakesTheRegimeACandidate() {
        feed(T0, 7, 2.0);

        assertThat(state.getRegimeState()).isEqualTo("CANDIDATE");
        assertThat(state.getRegimeSince()).isNotNull();
    }

    @Test
    void aCandidateBecomesShiftedOnlyAfterThePersistencePeriod() {
        Instant now = feed(T0, 7, 2.0);
        assertThat(state.getRegimeState()).isEqualTo("CANDIDATE");

        // 12h depois ainda é candidato (persistência = 24h)
        tracker.update(state, 2.0, state.getRegimeSince().plus(Duration.ofHours(12)), false);
        assertThat(state.getRegimeState()).isEqualTo("CANDIDATE");

        // mantém o desvio até passar de 24h
        RegimeTracker.Outcome outcome = null;
        for (int i = 0; i < 10 && !"SHIFTED".equals(state.getRegimeState()); i++) {
            outcome = tracker.update(state, 2.0, state.getRegimeSince().plus(Duration.ofHours(25)), false);
        }
        assertThat(state.getRegimeState()).isEqualTo("SHIFTED");
        assertThat(outcome.enteredShifted()).isTrue();
    }

    @Test
    void aCandidateIsCancelledWhenConsumptionGoesBackToNormal() {
        feed(T0, 7, 2.0);
        assertThat(state.getRegimeState()).isEqualTo("CANDIDATE");

        feed(T0.plusSeconds(3600), 3, -3.0);

        assertThat(state.getRegimeState()).isEqualTo("NORMAL");
        assertThat(state.getRegimeSince()).isNull();
    }

    @Test
    void idleWindowsWithoutAResidualNeverChangeTheRegime() {
        feed(T0, 7, 2.0);
        double before = state.getCusumPos();

        for (int i = 0; i < 100; i++) tracker.update(state, null, T0.plusSeconds(60L * i), false);

        assertThat(state.getRegimeState()).isEqualTo("CANDIDATE");
        assertThat(state.getCusumPos()).isEqualTo(before);
    }

    @Test
    void aShiftedRegimeReturnsToNormalWithoutAnAlertAfterEnoughNormalWindows() {
        state.setRegimeState("SHIFTED");
        state.setRegimeSince(T0);
        state.setCounter("direction", 1);
        RegimeTracker.Outcome last = null;

        for (int i = 0; i < 12; i++) last = tracker.update(state, -1.0, T0.plusSeconds(3600L + i * 300L), false);
        assertThat(state.getRegimeState()).isEqualTo("RETURNING");

        for (int i = 0; i < 12; i++) last = tracker.update(state, -1.0, T0.plusSeconds(20000L + i * 300L), false);

        assertThat(state.getRegimeState()).isEqualTo("NORMAL");
        assertThat(last.returned()).isTrue();
    }

    @Test
    void aRegimeThatLastsThirtyDaysBecomesTheBaseline() {
        state.setRegimeState("SHIFTED");
        state.setRegimeSince(T0);
        state.setCounter("direction", 1);

        var outcome = tracker.update(state, 2.0, T0.plus(Duration.ofDays(31)), false);

        assertThat(outcome.promoted()).isTrue();
        assertThat(state.getRegimeState()).isEqualTo("NORMAL");
    }

    @Test
    void aDataGapResetsTheAccumulatedSums() {
        feed(T0, 3, 2.0);
        assertThat(state.getCusumPos()).isPositive();

        tracker.update(state, 0.0, T0.plusSeconds(7200), true);

        assertThat(state.getCusumPos()).isZero();
    }

    @Test
    void aDropInConsumptionAlsoStartsACandidateInTheOppositeDirection() {
        feed(T0, 5, -3.0);

        assertThat(state.getRegimeState()).isEqualTo("CANDIDATE");
        assertThat(state.counter("direction")).isEqualTo(-1);
    }
}
