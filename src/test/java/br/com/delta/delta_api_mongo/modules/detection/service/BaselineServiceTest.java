package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.repository.UserHourBaselineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaselineServiceTest {
    private UserHourBaselineRepository repository;
    private BaselineService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserHourBaselineRepository.class);
        service = new BaselineService(repository, properties());
    }

    @Test
    void hourOfWeekStartsOnMondayMidnightInTheConfiguredZone() {
        assertThat(BaselineService.hourOfWeek(MONDAY_MIDNIGHT, ZONE)).isZero();
        assertThat(BaselineService.hourOfWeek(MONDAY_MIDNIGHT.plusSeconds(3 * 3600), ZONE)).isEqualTo(3);
        // domingo 23h local = 167
        assertThat(BaselineService.hourOfWeek(MONDAY_MIDNIGHT.plusSeconds((6 * 24 + 23) * 3600L), ZONE)).isEqualTo(167);
    }

    @Test
    void slotIsCreatedEmptyWhenThereIsNoDocument() {
        when(repository.findByDeviceIdAndHourOfWeek("DEV-1", 5)).thenReturn(Optional.empty());

        var slot = service.slot("DEV-1", 5);

        assertThat(slot.getDeviceId()).isEqualTo("DEV-1");
        assertThat(slot.getHourOfWeek()).isEqualTo(5);
        assertThat(slot.getSamples()).isEmpty();
        assertThat(slot.getRegimeState()).isEqualTo("NORMAL");
    }

    @Test
    void acceptSlowKeepsMedianAndMadOfTheSamples() {
        var slot = new UserHourBaselineDocument("DEV-1", 3);

        for (double liters : new double[]{10, 12, 11, 40, 10}) service.acceptSlow(slot, liters);

        assertThat(slot.getMedian()).isEqualTo(11.0);
        assertThat(slot.getMad()).isEqualTo(1.0);
        assertThat(slot.getCount()).isEqualTo(5);
    }

    @Test
    void acceptSlowKeepsOnlyTheLatestSamples() {
        var slot = new UserHourBaselineDocument("DEV-1", 3);

        for (int i = 1; i <= 40; i++) service.acceptSlow(slot, i);

        assertThat(slot.getSamples()).hasSize(28);
        assertThat(slot.getSamples().get(0)).isEqualTo(13.0);
        assertThat(slot.getCount()).isEqualTo(40);
    }

    @Test
    void hasSlowHistoryNeedsTheMinimumNumberOfSamples() {
        var slot = new UserHourBaselineDocument("DEV-1", 3);
        for (int i = 0; i < 3; i++) service.acceptSlow(slot, 5);
        assertThat(service.hasSlowHistory(slot)).isFalse();

        service.acceptSlow(slot, 5);

        assertThat(service.hasSlowHistory(slot)).isTrue();
    }

    @Test
    void fastBaselineStartsAtTheFirstValueAndFollowsRecentOnes() {
        var slot = new UserHourBaselineDocument("DEV-1", 3);

        service.updateFast(slot, 10);
        assertThat(slot.getEwmaMean()).isEqualTo(10.0);
        assertThat(slot.getEwmaVar()).isZero();

        service.updateFast(slot, 20);
        assertThat(slot.getEwmaMean()).isEqualTo(14.0, offset(1e-9));
        assertThat(slot.getEwmaVar()).isGreaterThan(0);
    }

    @Test
    void floorsOnlyTrackTheNightWindowsAndTheActiveFlow() {
        var slot = new UserHourBaselineDocument("DEV-1", 3);

        service.updateFloors(slot, 0.0, false);
        assertThat(slot.getNightFloorLpm()).isNull();
        assertThat(slot.getMinFlowMedian()).isNull();

        service.updateFloors(slot, 0.2, true);
        assertThat(slot.getNightFloorLpm()).isEqualTo(0.2);
        assertThat(slot.getMinFlowMedian()).isEqualTo(0.2);
    }

    @Test
    void promotingTheFastBaselineReplacesTheSlowOneInEverySlot() {
        var slot = new UserHourBaselineDocument("DEV-1", 3);
        service.acceptSlow(slot, 5);
        slot.setEwmaMean(20.0);
        slot.setEwmaVar(9.0);
        var noFast = new UserHourBaselineDocument("DEV-1", 4);
        service.acceptSlow(noFast, 7);
        when(repository.findByDeviceIdAndHourOfWeekGreaterThanEqual("DEV-1", 0)).thenReturn(List.of(slot, noFast));

        service.promoteFastToSlow("DEV-1");

        assertThat(slot.getMedian()).isEqualTo(20.0);
        assertThat(service.hasSlowHistory(slot)).isTrue();
        assertThat(slot.isSlowReady()).isTrue();
        assertThat(slot.getMad()).isEqualTo(3.0 / 1.4826, offset(1e-9));
        assertThat(noFast.getMedian()).isEqualTo(7.0);
        verify(repository).saveAll(any());
    }

    @Test
    void deviceStateUsesTheReservedSlot() {
        when(repository.findByDeviceIdAndHourOfWeek("DEV-1", -1)).thenReturn(Optional.empty());

        assertThat(service.deviceState("DEV-1").getHourOfWeek()).isEqualTo(UserHourBaselineDocument.DEVICE_STATE_SLOT);
    }
}
