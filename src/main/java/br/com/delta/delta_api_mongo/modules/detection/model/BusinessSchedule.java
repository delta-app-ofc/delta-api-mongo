package br.com.delta.delta_api_mongo.modules.detection.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Expediente de um imóvel comercial: turnos (início e fim do dia) e dias da semana em que funciona.
 * Fora do expediente, água correndo é suspeita; dentro dele é consumo normal do negócio.
 */
public record BusinessSchedule(List<Shift> shifts, Set<DayOfWeek> operationDays) {

    public record Shift(LocalTime start, LocalTime end) {
    }

    /** Sem turnos cadastrados não dá para saber o horário de funcionamento: o detector não usa a regra. */
    public boolean isDefined() {
        return shifts != null && !shifts.isEmpty();
    }

    public boolean isOpen(Instant instant, ZoneId zone) {
        ZonedDateTime local = instant.atZone(zone);
        // Sem dias cadastrados, considera que funciona todos os dias.
        if (operationDays != null && !operationDays.isEmpty() && !operationDays.contains(local.getDayOfWeek())) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        return shifts.stream().anyMatch(shift -> !time.isBefore(shift.start()) && time.isBefore(shift.end()));
    }
}
