package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class DeviceOwnerTest {
    private static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-07-13T12:00:00Z");

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    @Test
    void payloadIsConvertedIntoTheOwnerWithTheBusinessSchedule() {
        var payload = new DeviceOwnerClient.OwnerPayload(11, "COMERCIAL", List.of(50, 51),
                List.of(new DeviceOwnerClient.ShiftPayload("08:00:00", "12:00:00"),
                        new DeviceOwnerClient.ShiftPayload("14:00", "18:00")),
                List.of("MONDAY", "TUESDAY"));

        DeviceOwner owner = payload.toOwner();

        assertThat(owner.isCommercial()).isTrue();
        assertThat(owner.isResidential()).isFalse();
        assertThat(owner.userIds()).containsExactly(50, 51);
        assertThat(owner.schedule().shifts()).hasSize(2);
        assertThat(owner.schedule().shifts().get(1).start()).isEqualTo(LocalTime.of(14, 0));
        assertThat(owner.schedule().operationDays()).containsExactlyInAnyOrder(java.time.DayOfWeek.MONDAY,
                java.time.DayOfWeek.TUESDAY);
    }

    @Test
    void payloadWithoutShiftsOrDaysProducesAnUndefinedSchedule() {
        var owner = new DeviceOwnerClient.OwnerPayload(10, "RESIDENCIAL", null, null, null).toOwner();

        assertThat(owner.isResidential()).isTrue();
        assertThat(owner.userIds()).isEmpty();
        assertThat(owner.schedule().isDefined()).isFalse();
    }

    @Test
    void clientWithoutUrlOrKeyIsNotConfiguredAndReturnsNothing() {
        var client = new DeviceOwnerClient(properties());

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.find("DEV-1")).isEmpty();
    }

    @Test
    void resolverCachesTheOwnerUntilTheTtlExpires() {
        var client = mock(DeviceOwnerClient.class);
        var owner = new DeviceOwner(10, "RESIDENCIAL", List.of(7), null);
        when(client.isConfigured()).thenReturn(true);
        when(client.find("DEV-1")).thenReturn(Optional.of(owner));
        var clock = new MovableClock();
        var resolver = new DeviceOwnerResolver(client, properties(), clock);

        assertThat(resolver.resolve("DEV-1")).contains(owner);
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(resolver.resolve("DEV-1")).contains(owner);
        verify(client, times(1)).find("DEV-1");

        clock.now = clock.now.plus(Duration.ofMinutes(31));
        resolver.resolve("DEV-1");
        verify(client, times(2)).find("DEV-1");
    }

    @Test
    void resolverTurnsAFailureIntoAnUnknownOwnerAndRetriesSoon() {
        var client = mock(DeviceOwnerClient.class);
        var calls = new AtomicInteger();
        when(client.isConfigured()).thenReturn(true);
        when(client.find("DEV-1")).thenAnswer(invocation -> {
            calls.incrementAndGet();
            throw new IllegalStateException("postgres fora do ar");
        });
        var clock = new MovableClock();
        var resolver = new DeviceOwnerResolver(client, properties(), clock);

        assertThat(resolver.resolve("DEV-1")).isEmpty();
        assertThat(resolver.resolve("DEV-1")).isEmpty();
        assertThat(calls).hasValue(1);

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        resolver.resolve("DEV-1");
        assertThat(calls).hasValue(2);
    }
}
