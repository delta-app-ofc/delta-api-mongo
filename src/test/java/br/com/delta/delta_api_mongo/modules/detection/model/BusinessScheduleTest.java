package br.com.delta.delta_api_mongo.modules.detection.model;

import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessScheduleTest {
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Test
    void isOpenInsideAnyShiftAndClosedBetweenShifts() {
        var schedule = new BusinessSchedule(List.of(
                new BusinessSchedule.Shift(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new BusinessSchedule.Shift(LocalTime.of(14, 0), LocalTime.of(18, 0))), Set.of());

        // segunda 2026-07-13: 10:00 local = 13:00Z
        assertThat(schedule.isOpen(Instant.parse("2026-07-13T13:00:00Z"), ZONE)).isTrue();
        assertThat(schedule.isOpen(Instant.parse("2026-07-13T16:00:00Z"), ZONE)).isFalse(); // 13:00 local, almoço
        assertThat(schedule.isOpen(Instant.parse("2026-07-13T18:00:00Z"), ZONE)).isTrue();  // 15:00 local
    }

    @Test
    void theEndOfAShiftIsExclusive() {
        var schedule = new BusinessSchedule(List.of(new BusinessSchedule.Shift(LocalTime.of(8, 0),
                LocalTime.of(18, 0))), Set.of());

        assertThat(schedule.isOpen(Instant.parse("2026-07-13T21:00:00Z"), ZONE)).isFalse(); // 18:00 local
        assertThat(schedule.isOpen(Instant.parse("2026-07-13T11:00:00Z"), ZONE)).isTrue();  // 08:00 local
    }

    @Test
    void isClosedOnDaysTheCompanyDoesNotOperate() {
        var schedule = new BusinessSchedule(List.of(new BusinessSchedule.Shift(LocalTime.of(8, 0),
                LocalTime.of(18, 0))), Set.of(DayOfWeek.MONDAY));

        assertThat(schedule.isOpen(Instant.parse("2026-07-13T15:00:00Z"), ZONE)).isTrue();  // segunda
        assertThat(schedule.isOpen(Instant.parse("2026-07-14T15:00:00Z"), ZONE)).isFalse(); // terça
    }

    @Test
    void noRegisteredDaysMeansEveryDay() {
        var schedule = new BusinessSchedule(List.of(new BusinessSchedule.Shift(LocalTime.of(8, 0),
                LocalTime.of(18, 0))), Set.of());

        assertThat(schedule.isOpen(Instant.parse("2026-07-19T15:00:00Z"), ZONE)).isTrue(); // domingo
    }

    @Test
    void isDefinedOnlyWithShifts() {
        assertThat(new BusinessSchedule(List.of(), Set.of()).isDefined()).isFalse();
        assertThat(new BusinessSchedule(null, Set.of()).isDefined()).isFalse();
    }
}
