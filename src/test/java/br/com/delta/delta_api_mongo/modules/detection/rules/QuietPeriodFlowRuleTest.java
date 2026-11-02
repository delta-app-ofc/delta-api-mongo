package br.com.delta.delta_api_mongo.modules.detection.rules;

import br.com.delta.delta_api_mongo.modules.detection.model.BusinessSchedule;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class QuietPeriodFlowRuleTest {
    private final QuietPeriodFlowRule rule = new QuietPeriodFlowRule(properties());
    // 02:00 em São Paulo (segunda-feira)
    private static final Instant NIGHT = MONDAY_MIDNIGHT.plusSeconds(2 * 3600);
    // 10:00 em São Paulo (segunda-feira)
    private static final Instant MORNING = MONDAY_MIDNIGHT.plusSeconds(10 * 3600);
    private static final BusinessSchedule WEEKDAYS_8_TO_18 = new BusinessSchedule(
            List.of(new BusinessSchedule.Shift(LocalTime.of(8, 0), LocalTime.of(18, 0))),
            Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

    @Test
    void aroundMidnightIsNightInTheConfiguredZone() {
        assertThat(rule.isNight(window(NIGHT, 0, 0))).isTrue();
        assertThat(rule.isNight(window(MORNING, 0, 0))).isFalse();
        // 03:00Z é meia-noite em São Paulo, não madrugada em UTC
        assertThat(rule.isNight(window(MONDAY_MIDNIGHT, 0, 0))).isTrue();
        // 05:00 local já não é madrugada: o fim (05h) é exclusivo
        assertThat(rule.isNight(window(Instant.parse("2026-07-13T08:00:00Z"), 0, 0))).isFalse();
        assertThat(rule.isNight(window(Instant.parse("2026-07-13T07:55:00Z"), 0, 0))).isTrue(); // 04:55 local
    }

    @Test
    void steadyFlowAtNightTriggersTheNightFloor() {
        var windows = newestFirst(run(NIGHT, 3, 0.5, 0.1));

        var evidence = rule.evaluateNight(windows, null);

        assertThat(evidence).isPresent();
        assertThat(evidence.get()).containsEntry("rule", "night_floor");
    }

    @Test
    void flowBelowTheLearnedFloorIsIgnored() {
        var windows = newestFirst(run(NIGHT, 3, 0.5, 0.1));

        // piso aprendido 0,1 L/min * fator 3 = 0,3 > 0,1
        assertThat(rule.evaluateNight(windows, 0.1)).isEmpty();
    }

    @Test
    void oneNightWindowWithoutFlowBreaksTheSequence() {
        var oldestFirst = new java.util.ArrayList<>(run(NIGHT, 3, 0.5, 0.1));
        oldestFirst.set(1, window(NIGHT.plusSeconds(300), 0.0, 0.0));

        assertThat(rule.evaluateNight(newestFirst(oldestFirst), null)).isEmpty();
    }

    @Test
    void daytimeFlowIsNotANightFlow() {
        assertThat(rule.evaluateNight(newestFirst(run(MORNING, 3, 0.5, 0.1)), null)).isEmpty();
    }

    @Test
    void flowOutsideBusinessHoursIsReportedWithItsOwnRule() {
        // 20:00 local, depois do fim do turno das 18h
        Instant evening = MONDAY_MIDNIGHT.plusSeconds(20 * 3600);

        var evidence = rule.evaluateOffHours(newestFirst(run(evening, 3, 0.5, 0.1)), null, WEEKDAYS_8_TO_18);

        assertThat(evidence).isPresent();
        assertThat(evidence.get()).containsEntry("rule", "off_hours_flow");
    }

    @Test
    void flowInsideTheShiftIsNormalBusinessUse() {
        assertThat(rule.evaluateOffHours(newestFirst(run(MORNING, 3, 5.0, 1.0)), null, WEEKDAYS_8_TO_18)).isEmpty();
    }

    @Test
    void aWeekendIsOutsideBusinessHoursWhenTheCompanyOnlyOpensOnWeekdays() {
        // sábado 10:00 local
        Instant saturday = MONDAY_MIDNIGHT.plusSeconds((5 * 24 + 10) * 3600L);

        assertThat(rule.evaluateOffHours(newestFirst(run(saturday, 3, 0.5, 0.1)), null, WEEKDAYS_8_TO_18)).isPresent();
    }

    @Test
    void withoutARegisteredScheduleThereIsNoOffHoursRule() {
        Instant evening = MONDAY_MIDNIGHT.plusSeconds(20 * 3600);

        assertThat(rule.evaluateOffHours(newestFirst(run(evening, 3, 0.5, 0.1)), null, null)).isEmpty();
        assertThat(rule.evaluateOffHours(newestFirst(run(evening, 3, 0.5, 0.1)), null,
                new BusinessSchedule(List.of(), Set.of()))).isEmpty();
    }
}
