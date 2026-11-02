package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import br.com.delta.delta_api_mongo.modules.detection.repository.UserHourBaselineRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import java.util.List;
import java.util.Map;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CohortAndWriterTest {
    private static UserHourBaselineDocument slot(double median, double mad) {
        var doc = new UserHourBaselineDocument("DEV-X", 10);
        doc.setMedian(median);
        doc.setMad(mad);
        doc.setCount(10);
        return doc;
    }

    @Test
    void cohortReferenceIsTheMedianOfTheOtherDevicesWhenThereAreEnoughOfThem() {
        var repository = mock(UserHourBaselineRepository.class);
        when(repository.findByHourOfWeekAndCountGreaterThanEqual(eq(10), anyInt(), any(Pageable.class)))
                .thenReturn(List.of(slot(2, 1), slot(4, 1), slot(6, 2), slot(8, 2), slot(100, 9)));
        var cohort = new CohortBaseline(repository, properties());

        var reference = cohort.reference(10);

        assertThat(reference).isPresent();
        assertThat(reference.get().median()).isEqualTo(6.0);
        assertThat(reference.get().mad()).isEqualTo(2.0);
        assertThat(reference.get().devices()).isEqualTo(5);
    }

    @Test
    void cohortIsUnavailableWithTooFewDevicesAndIsCachedPerHour() {
        var repository = mock(UserHourBaselineRepository.class);
        when(repository.findByHourOfWeekAndCountGreaterThanEqual(eq(10), anyInt(), any(Pageable.class)))
                .thenReturn(List.of(slot(2, 1), slot(4, 1)));
        var cohort = new CohortBaseline(repository, properties());

        assertThat(cohort.reference(10)).isEmpty();
        assertThat(cohort.reference(10)).isEmpty();

        verify(repository, times(1)).findByHourOfWeekAndCountGreaterThanEqual(eq(10), anyInt(), any(Pageable.class));
    }

    @Test
    void writerUpdatesOnlyTheDetectorFieldsOfTheWindow() {
        var template = mock(MongoTemplate.class);
        var writer = new DetectionResultWriter(template);
        var id = new ObjectId();
        var result = new DetectionResult(true, List.of("continuous_flow"), Map.of("k", "v"), null);

        writer.write(id, result, "1/test");

        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(template).updateFirst(any(Query.class), update.capture(), eq(ConsumptionSummaryDocument.class));
        var set = update.getValue().getUpdateObject().get("$set");
        assertThat(set.toString()).contains("anomaly_detected=true", "anomaly_reasons", "detector_version=1/test")
                .doesNotContain("detection_error");
    }

    @Test
    void writerStoresTheErrorWhenTheWindowWasNotEvaluated() {
        var template = mock(MongoTemplate.class);
        var writer = new DetectionResultWriter(template);

        writer.write(new ObjectId(), DetectionResult.notEvaluated("sem baseline", Map.of()), "1/test");

        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(template).updateFirst(any(Query.class), update.capture(), eq(ConsumptionSummaryDocument.class));
        assertThat(update.getValue().getUpdateObject().get("$set").toString())
                .contains("anomaly_detected=null", "detection_error=sem baseline");
    }
}
