package br.com.delta.delta_api_mongo.modules.detection.rules;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PageHinkleyTest {
    @Test
    void smallDeviationsBelowTheSlackNeverAccumulate() {
        var state = new PageHinkley.State(0, 0);

        for (int i = 0; i < 100; i++) state = PageHinkley.update(state, 0.3, 0.5, 6);

        assertThat(state.pos()).isZero();
        assertThat(state.neg()).isZero();
    }

    @Test
    void aSustainedStepAccumulatesUntilItCrossesTheThreshold() {
        var state = new PageHinkley.State(0, 0);
        int windows = 0;

        while (state.pos() <= 8.0 && windows < 100) {
            state = PageHinkley.update(state, 2.0, 0.5, 6);
            windows++;
        }

        assertThat(windows).isEqualTo(6); // 1,5 por janela: 9 > 8 na sexta
        assertThat(state.neg()).isZero();
    }

    @Test
    void aDropAccumulatesInTheNegativeSum() {
        var state = new PageHinkley.State(0, 0);

        for (int i = 0; i < 4; i++) state = PageHinkley.update(state, -3.0, 0.5, 6);

        assertThat(state.neg()).isEqualTo(10.0);
        assertThat(state.pos()).isZero();
    }

    @Test
    void oneHugeOutlierIsClippedAndCannotTriggerAloneByItself() {
        var state = PageHinkley.update(new PageHinkley.State(0, 0), 500.0, 0.5, 6);

        assertThat(state.pos()).isEqualTo(5.5);
    }

    @Test
    void theSumGoesBackToZeroWhenConsumptionReturnsToNormal() {
        var state = new PageHinkley.State(3.0, 0);

        state = PageHinkley.update(state, -3.0, 0.5, 6);

        assertThat(state.pos()).isZero();
    }
}
