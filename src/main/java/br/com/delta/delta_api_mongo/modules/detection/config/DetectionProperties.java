package br.com.delta.delta_api_mongo.modules.detection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.ZoneId;

/**
 * Parâmetros do detector de anomalias (prefixo delta.detection).
 * Os valores padrão são ponto de partida; os limiares finais saem da calibração com cenários sintéticos.
 * Mudou algum valor que altera o resultado? Aumente configVersion: ele é gravado em cada janela e baseline.
 */
@ConfigurationProperties(prefix = "delta.detection")
public record DetectionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1") String configVersion,
        @DefaultValue("America/Sao_Paulo") ZoneId zone,
        @DefaultValue Window window,
        @DefaultValue Baseline baseline,
        @DefaultValue ZScore zscore,
        @DefaultValue ContinuousFlow continuousFlow,
        @DefaultValue Night night,
        @DefaultValue PageHinkley pageHinkley,
        @DefaultValue Regime regime,
        @DefaultValue Alert alert,
        @DefaultValue Cohort cohort,
        @DefaultValue Owner owner
) {
    /** Janela esperada do hardware e tolerâncias para qualidade dos dados. */
    public record Window(
            @DefaultValue("5") int minutes,
            @DefaultValue("30") int toleranceSeconds,
            @DefaultValue("15") int maxGapMinutes
    ) {
    }

    /** Baseline lento (mediana/MAD das últimas amostras do slot) e rápido (média móvel exponencial). */
    public record Baseline(
            @DefaultValue("28") int maxSamples,
            @DefaultValue("4") int minSamples,
            @DefaultValue("0.4") double ewmaAlpha,
            @DefaultValue("0.1") double floorAlpha
    ) {
    }

    /** Z-score robusto: (litros - mediana) / (1,4826 * MAD), com escala mínima para o caso MAD = 0. */
    public record ZScore(
            @DefaultValue("4.0") double threshold,
            @DefaultValue("5.0") double minScaleLiters,
            @DefaultValue("4") int confirmWindows
    ) {
    }

    /** Fluxo contínuo: janelas seguidas com litros > 0 e vazão quase constante. */
    public record ContinuousFlow(
            @DefaultValue("6") int minWindows,
            @DefaultValue("0.35") double maxCv
    ) {
    }

    /** Consumo noturno (Minimum Night Flow): só imóveis residenciais. */
    public record Night(
            @DefaultValue("0") int startHour,
            @DefaultValue("5") int endHour,
            @DefaultValue("3") int minWindows,
            @DefaultValue("0.05") double minLpm,
            @DefaultValue("3.0") double floorFactor
    ) {
    }

    /** Page-Hinkley/CUSUM sobre o resíduo padronizado de cada hora: delta é a folga, lambda o limiar de alarme. */
    public record PageHinkley(
            @DefaultValue("0.5") double delta,
            @DefaultValue("10.0") double lambda,
            @DefaultValue("4.0") double clip
    ) {
    }

    /**
     * Mudança de regime (visita, novo normal) e retorno ao normal anterior.
     * returnWindows conta horas seguidas de consumo de volta ao baseline antigo; minSigmaLiters é o desvio
     * mínimo por janela usado para padronizar o consumo (evita dividir por quase zero em horas sempre vazias).
     */
    public record Regime(
            @DefaultValue("24") int persistenceHours,
            @DefaultValue("30") int promoteDays,
            @DefaultValue("12") int returnWindows,
            @DefaultValue("1.0") double minSigmaLiters
    ) {
    }

    /** Alertas em alerts_history: quantas janelas normais seguidas (3 h) encerram um alerta aberto. */
    public record Alert(@DefaultValue("36") int resolveWindows) {
    }

    /** Cold start: usa a mediana dos outros dispositivos enquanto o slot não tem histórico próprio. */
    public record Cohort(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5") int minDevices,
            @DefaultValue("7") int minCount,
            @DefaultValue("10") int cacheMinutes
    ) {
    }

    /** Consulta do dono do dispositivo na delta-api-postgres (vazia = dono desconhecido). */
    public record Owner(
            @DefaultValue("") String url,
            @DefaultValue("") String serviceKey,
            @DefaultValue("2000") int timeoutMs,
            @DefaultValue("30") int cacheMinutes
    ) {
    }
}
