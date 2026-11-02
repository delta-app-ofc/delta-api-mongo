package br.com.delta.delta_api_mongo.modules.detection.rules;

import br.com.delta.delta_api_mongo.modules.detection.model.WindowReading;
import java.time.Duration;
import java.util.List;

/** Confere se as janelas são vizinhas (sem buraco): janela perdida ou dispositivo offline quebra a sequência. */
final class Contiguous {
    private Contiguous() {
    }

    /** newestFirst: a janela i+1 deve terminar (quase) quando a janela i começa. */
    static boolean isContiguous(List<WindowReading> newestFirst, int toleranceSeconds) {
        for (int i = 0; i + 1 < newestFirst.size(); i++) {
            long gap = Math.abs(Duration.between(newestFirst.get(i + 1).end(), newestFirst.get(i).start()).toSeconds());
            if (gap > toleranceSeconds) return false;
        }
        return true;
    }
}
