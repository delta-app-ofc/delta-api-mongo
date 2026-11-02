package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.rules.PageHinkley;
import java.time.Duration;
import java.time.Instant;

/**
 * Acompanha mudanças de regime do consumo (visita, novo morador, férias) para não confundir o "novo normal"
 * com anomalia. Estados: NORMAL -> CANDIDATE -> SHIFTED -> RETURNING -> NORMAL.
 * - CANDIDATE: o Page-Hinkley acusou um degrau, mas ainda não durou o suficiente.
 * - SHIFTED: o degrau persistiu; o baseline rápido vira a referência e o lento fica congelado.
 * - RETURNING: o consumo voltou ao baseline antigo; confirma o retorno sem gerar alerta.
 * Um regime que dura promoteDays vira o baseline (o novo normal).
 * O resíduo chega uma vez por hora (consumo da hora comparado ao esperado). Os pisos de vazão (noturno e de
 * uso) são acompanhados à parte e nunca entram no baseline do regime: um piso baixo e constante não diz nada
 * sobre mudança de rotina; picos mais altos com piso normal indicam visita, não vazamento.
 */
public class RegimeTracker {
    static final String DIRECTION = "direction";
    static final String RETURN_COUNT = "return_count";

    private final DetectionProperties properties;

    public RegimeTracker(DetectionProperties properties) {
        this.properties = properties;
    }

    /** O que mudou nesta janela. */
    public record Outcome(boolean enteredShifted, boolean returned, boolean promoted) {
    }

    /**
     * Atualiza o estado do dispositivo.
     * @param residual desvio padronizado em relação ao baseline LENTO (null se não há baseline ou janela ociosa)
     */
    public Outcome update(UserHourBaselineDocument state, Double residual, Instant now, boolean dataGap) {
        var ph = properties.pageHinkley();
        var current = new PageHinkley.State(state.getCusumPos(), state.getCusumNeg());
        if (dataGap) current = new PageHinkley.State(0, 0);
        if (residual != null) current = PageHinkley.update(current, residual, ph.delta(), ph.clip());
        state.setCusumPos(current.pos());
        state.setCusumNeg(current.neg());
        if (residual == null) return new Outcome(false, false, false);

        int direction = state.counter(DIRECTION) == 0 ? 1 : state.counter(DIRECTION);
        double stat = direction > 0 ? current.pos() : current.neg();
        Duration elapsed = state.getRegimeSince() == null ? Duration.ZERO : Duration.between(state.getRegimeSince(), now);
        String regime = state.getRegimeState() == null ? "NORMAL" : state.getRegimeState();

        switch (regime) {
            case "CANDIDATE" -> {
                if (stat <= 0) {
                    reset(state, "NORMAL");
                } else if (elapsed.toHours() >= properties.regime().persistenceHours() && stat > ph.lambda() / 2) {
                    state.setRegimeState("SHIFTED");
                    state.setRegimeSince(now);
                    state.setCounter(RETURN_COUNT, 0);
                    return new Outcome(true, false, false);
                }
            }
            case "SHIFTED" -> {
                state.setCounter(RETURN_COUNT, stat <= 0 ? state.counter(RETURN_COUNT) + 1 : 0);
                if (state.counter(RETURN_COUNT) >= properties.regime().returnWindows()) {
                    state.setRegimeState("RETURNING");
                    state.setRegimeSince(now);
                    state.setCounter(RETURN_COUNT, 0);
                } else if (elapsed.toDays() >= properties.regime().promoteDays()) {
                    reset(state, "NORMAL");
                    return new Outcome(false, false, true);
                }
            }
            case "RETURNING" -> {
                if (stat > ph.lambda()) {
                    state.setRegimeState("SHIFTED");
                    state.setRegimeSince(now);
                    state.setCounter(RETURN_COUNT, 0);
                } else {
                    state.setCounter(RETURN_COUNT, state.counter(RETURN_COUNT) + 1);
                    if (state.counter(RETURN_COUNT) >= properties.regime().returnWindows()) {
                        reset(state, "NORMAL");
                        return new Outcome(false, true, false);
                    }
                }
            }
            default -> {
                if (current.pos() > ph.lambda() || current.neg() > ph.lambda()) {
                    boolean up = current.pos() > ph.lambda();
                    state.setRegimeState("CANDIDATE");
                    state.setRegimeSince(now);
                    state.setCounter(DIRECTION, up ? 1 : -1);
                    // Recomeça a soma: o candidato precisa se sustentar a partir de agora.
                    state.setCusumPos(0.0);
                    state.setCusumNeg(0.0);
                }
            }
        }
        return new Outcome(false, false, false);
    }

    private static void reset(UserHourBaselineDocument state, String regime) {
        state.setRegimeState(regime);
        state.setRegimeSince(null);
        state.setCounter(DIRECTION, 0);
        state.setCounter(RETURN_COUNT, 0);
        state.setCusumPos(0.0);
        state.setCusumNeg(0.0);
    }
}
