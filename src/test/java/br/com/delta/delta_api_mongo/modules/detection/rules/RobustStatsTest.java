package br.com.delta.delta_api_mongo.modules.detection.rules;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RobustStatsTest {
    @Test
    void medianOfAnOddAndAnEvenNumberOfValues() {
        assertThat(RobustStats.median(List.of(5.0, 1.0, 3.0))).isEqualTo(3.0);
        assertThat(RobustStats.median(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
    }

    @Test
    void medianRejectsAnEmptyList() {
        assertThatThrownBy(() -> RobustStats.median(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void medianIgnoresExtremeValuesWhileTheMeanDoesNot() {
        var values = List.of(10.0, 11.0, 9.0, 10.0, 1000.0);

        assertThat(RobustStats.median(values)).isEqualTo(10.0);
        assertThat(values.stream().mapToDouble(Double::doubleValue).average().orElse(0)).isGreaterThan(200.0);
    }

    @Test
    void madIsTheMedianOfTheDistancesToTheMedian() {
        var values = List.of(1.0, 2.0, 3.0, 4.0, 100.0);

        assertThat(RobustStats.mad(values, RobustStats.median(values))).isEqualTo(1.0);
    }

    @Test
    void robustZUsesTheScaledMad() {
        // 1,4826 * 2 = 2,9652 -> (10 - 4) / 2,9652
        assertThat(RobustStats.robustZ(10, 4, 2, 0.1)).isEqualTo(6 / (1.4826 * 2), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void robustZDoesNotDivideByZeroWhenTheMadIsZero() {
        double z = RobustStats.robustZ(3.0, 0.0, 0.0, 0.5);

        assertThat(z).isFinite().isEqualTo(6.0);
    }

    @Test
    void robustZIsNegativeBelowTheMedian() {
        assertThat(RobustStats.robustZ(1, 5, 1, 0.5)).isNegative();
    }
}
