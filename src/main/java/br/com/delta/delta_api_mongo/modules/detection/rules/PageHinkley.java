package br.com.delta.delta_api_mongo.modules.detection.rules;

/**
 * Page-Hinkley / CUSUM: soma os desvios padronizados (resíduos) janela a janela. Um desvio pequeno e isolado
 * é absorvido pela folga delta; um desvio pequeno mas persistente (um degrau de consumo) vai somando até
 * passar do limiar lambda. Uma soma serve para subidas (pos) e outra para quedas (neg).
 */
public final class PageHinkley {
    private PageHinkley() {
    }

    public record State(double pos, double neg) {
    }

    public static State update(State previous, double residual, double delta, double clip) {
        double bounded = Math.max(-clip, Math.min(clip, residual));
        double pos = Math.max(0, previous.pos() + bounded - delta);
        double neg = Math.max(0, previous.neg() - bounded - delta);
        return new State(pos, neg);
    }
}
