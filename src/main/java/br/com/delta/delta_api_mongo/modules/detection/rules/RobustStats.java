package br.com.delta.delta_api_mongo.modules.detection.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Estatística robusta: a mediana e o MAD (desvio absoluto mediano) quase não mudam com valores
 * extremos, ao contrário da média e do desvio padrão. Por isso um vazamento não contamina o "normal".
 */
public final class RobustStats {
    /** Fator que faz 1,4826 * MAD estimar o desvio padrão de dados com distribuição normal. */
    public static final double MAD_TO_SIGMA = 1.4826;

    private RobustStats() {
    }

    public static double median(List<Double> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("Sem valores para calcular a mediana.");
        var sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    public static double mad(List<Double> values, double median) {
        return median(values.stream().map(value -> Math.abs(value - median)).toList());
    }

    /**
     * Z-score robusto. Se o MAD for 0 (valores todos iguais, comum quando o consumo é sempre 0 numa hora),
     * a divisão explodiria; por isso a escala nunca fica abaixo de minScale.
     */
    public static double robustZ(double value, double median, double mad, double minScale) {
        double scale = Math.max(MAD_TO_SIGMA * mad, minScale);
        return (value - median) / scale;
    }
}
