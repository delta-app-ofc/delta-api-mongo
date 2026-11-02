package br.com.delta.delta_api_mongo.modules.detection.rules;

import br.com.delta.delta_api_mongo.modules.detection.model.WindowReading;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class ContinuousFlowRuleTest {
    private static final Instant T0 = Instant.parse("2026-07-13T15:00:00Z");
    private final ContinuousFlowRule rule = new ContinuousFlowRule(properties());

    @Test
    void sixConsecutiveWindowsWithSteadyFlowAreALeak() {
        var windows = newestFirst(run(T0, 6, 1.0, 0.2));

        var evidence = rule.evaluate(windows);

        assertThat(evidence).isPresent();
        assertThat(evidence.get()).containsEntry("rule", "continuous_flow").containsEntry("windows", 6);
    }

    @Test
    void fiveWindowsAreNotEnough() {
        assertThat(rule.evaluate(newestFirst(run(T0, 5, 1.0, 0.2)))).isEmpty();
    }

    @Test
    void aWindowWithZeroLitersBreaksTheRun() {
        var oldestFirst = new ArrayList<>(run(T0, 6, 1.0, 0.2));
        oldestFirst.set(2, window(T0.plusSeconds(600), 0.0, 0.0));

        assertThat(rule.evaluate(newestFirst(oldestFirst))).isEmpty();
    }

    @Test
    void aMissingWindowIsNotAContinuousFlow() {
        var oldestFirst = new ArrayList<>(run(T0, 6, 1.0, 0.2));
        // remove a 3a janela: fica um buraco de 5 minutos (janela perdida / dispositivo offline)
        oldestFirst.remove(2);
        oldestFirst.add(window(T0.plusSeconds(1800), 1.0, 0.2));

        assertThat(rule.evaluate(newestFirst(oldestFirst))).isEmpty();
    }

    @Test
    void anIrregularFlowLikeANormalUseIsNotALeak() {
        var windows = new ArrayList<WindowReading>();
        double[] lpm = {0.2, 6.0, 0.4, 9.0, 0.3, 5.0};
        for (int i = 0; i < 6; i++) windows.add(window(T0.plusSeconds(300L * i), lpm[i] * 5, lpm[i]));

        assertThat(rule.evaluate(newestFirst(windows))).isEmpty();
    }

    @Test
    void usesOnlyTheNewestWindowsWhenThereIsLongerHistory() {
        var oldestFirst = new ArrayList<>(run(T0, 3, 0.0, 0.0));
        oldestFirst.addAll(run(T0.plusSeconds(900), 6, 1.0, 0.2));

        assertThat(rule.evaluate(newestFirst(oldestFirst))).isPresent();
    }
}
