package br.com.delta.delta_api_mongo.modules.detection;
import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Busca determinística e opt-in: nunca conecta banco, LLM ou gera limiares em produção automaticamente. */
@EnabledIfSystemProperty(named="delta.calibrate",matches="true")
class GridCalibrationTest {
    @Test void searchesThresholdDeltaLambdaAndPersistence() throws Exception {
        var rows=new ArrayList<String>();
        rows.add("threshold,delta,lambda,confirmation,false_house_weeks,recall,precision,delay_minutes");
        double best=Double.POSITIVE_INFINITY;String chosen=null;
        for(double z:new double[]{3.5,4.0,4.5}) for(double delta:new double[]{0.3,0.5})
        for(double lambda:new double[]{8,10}) for(int confirmation:new int[]{2,4}) {
            var p=DetectionTestSupport.properties();
            var config=new DetectionProperties(true,"calibrate-seed-42",p.zone(),p.window(),p.baseline(),
                new DetectionProperties.ZScore(z,p.zscore().minScaleLiters(),confirmation),p.continuousFlow(),p.night(),
                new DetectionProperties.PageHinkley(delta,lambda,p.pageHinkley().clip()),p.regime(),p.alert(),p.cohort(),p.owner());
            int falseWindows=0,detected=0,trueWindows=0;long delay=10080;
            for(int kind=0;kind<2;kind++) {
                var home=new SyntheticHome(42,DetectionTestSupport.ZONE,false);
                var harness=new DetectorHarness(config,Optional.of(new DeviceOwner(1,"RESIDENCIAL",List.of(7),null)));
                Instant leakStart=DetectionTestSupport.MONDAY_MIDNIGHT.plus(Duration.ofDays(14));
                boolean alarmed=false;
                for(int i=0;i<21*288;i++) {
                    if(kind==1 && i==14*288) home.leak(0.1);
                    var sample=home.next(DetectionTestSupport.MONDAY_MIDNIGHT.plusSeconds(300L*i));
                    var result=harness.feed("CAL",sample.start(),sample.liters(),sample.lpm());
                    if(i>=14*288 && Boolean.TRUE.equals(result.anomalyDetected())) {
                        if(kind==0) falseWindows++;
                        else {trueWindows++; if(!alarmed) {detected++;delay=Duration.between(leakStart,sample.start().plusSeconds(300)).toMinutes();alarmed=true;}}
                    }
                }
            }
            double precision=trueWindows/(double)Math.max(1,trueWindows+falseWindows);
            double recall=trueWindows/(double)(7*288);
            rows.add(z+","+delta+","+lambda+","+confirmation+","+(falseWindows>0?1:0)+","+recall+","+precision+","+delay);
            double score=(falseWindows>0?100000:0)+(detected==0?100000:0)+delay;
            if(score<best) {best=score;chosen="delta.detection.zscore.threshold="+z+"\ndelta.detection.zscore.confirm-windows="+confirmation
                +"\ndelta.detection.page-hinkley.delta="+delta+"\ndelta.detection.page-hinkley.lambda="+lambda+"\n";}
        }
        Path target=Path.of("target");Files.createDirectories(target);
        Files.write(target.resolve("calibration-grid.csv"),rows);
        Files.writeString(target.resolve("detector-config-candidate.properties"),"# Sintético, seed 42, 21 dias; candidato sujeito a revisão.\n"+chosen);
        assertThat(best).isLessThan(100000);
    }
}
