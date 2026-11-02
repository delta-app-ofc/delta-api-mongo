package br.com.delta.delta_api_mongo.modules.detection;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Gerador de consumo sintético, com semente fixa (resultado sempre igual). Cada janela de 5 minutos vira
 * litros e vazão média. O perfil imita uma casa: banhos e cozinha de manhã e à noite, pouco uso à tarde e
 * quase nada de madrugada. Usos são rajadas curtas (1 a 2 janelas).
 */
public class SyntheticHome {
    public record Sample(Instant start, double liters, double lpm) {
    }

    public static final int WINDOW_SECONDS = 300;

    private final Random random;
    private final ZoneId zone;
    /** Multiplicadores de uso (frequência e volume) aplicados a um intervalo: visita, férias etc. */
    private double usageFactor = 1.0;
    private double volumeFactor = 1.0;
    /** Vazamento constante somado a todas as janelas, em L/min. */
    private double leakLpm = 0.0;
    private Predicate<Instant> offline = instant -> false;
    private final boolean business;
    /** Janelas sem uso depois de cada uso: uma pessoa não fica 30 minutos seguidos com a água correndo igual. */
    private int cooldown = 0;

    public SyntheticHome(long seed, ZoneId zone, boolean business) {
        this.random = new Random(seed);
        this.zone = zone;
        this.business = business;
    }

    public SyntheticHome usage(double frequency, double volume) {
        this.usageFactor = frequency;
        this.volumeFactor = volume;
        return this;
    }

    public SyntheticHome leak(double lpm) {
        this.leakLpm = lpm;
        return this;
    }

    public SyntheticHome offlineWhen(Predicate<Instant> offline) {
        this.offline = offline;
        return this;
    }

    private double useProbability(Instant start) {
        var local = start.atZone(zone);
        int hour = local.getHour();
        if (business) {
            boolean weekday = local.getDayOfWeek().getValue() <= 5;
            return weekday && hour >= 8 && hour < 18 ? 0.35 : 0.0;
        }
        if (hour < 5) return 0.0;
        if (hour < 9) return 0.30;
        if (hour < 17) return 0.08;
        if (hour < 23) return 0.25;
        return 0.04;
    }

    /** Gera a próxima janela; retorna null se o dispositivo estiver offline (a janela se perde). */
    public Sample next(Instant start) {
        double probability = Math.min(0.9, useProbability(start) * usageFactor);
        double liters = 0.0;
        if (cooldown > 0) {
            cooldown--;
        } else if (random.nextDouble() < probability) {
            liters = Math.max(0.5, (4.0 + random.nextGaussian() * 1.5) * volumeFactor);
            cooldown = 2;
        }
        liters += leakLpm * (WINDOW_SECONDS / 60.0);
        if (offline.test(start)) return null;
        return new Sample(start, liters, liters / (WINDOW_SECONDS / 60.0));
    }

    public static Instant at(Instant origin, int day, int hour, int minute) {
        return origin.plus(Duration.ofDays(day)).plus(Duration.ofHours(hour)).plus(Duration.ofMinutes(minute));
    }
}
