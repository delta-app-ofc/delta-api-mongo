package br.com.delta.delta_api_mongo.modules.detection.repository;

import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface UserHourBaselineRepository extends MongoRepository<UserHourBaselineDocument, ObjectId> {
    java.util.List<UserHourBaselineDocument> findByCohortKeyAndHourOfWeekAndCountGreaterThanEqualAndDeviceIdNot(
            String cohortKey, int hour, int count, String deviceId, org.springframework.data.domain.Pageable pageable);

    Optional<UserHourBaselineDocument> findByDeviceIdAndHourOfWeek(String deviceId, int hourOfWeek);

    List<UserHourBaselineDocument> findByDeviceIdAndHourOfWeekGreaterThanEqual(String deviceId, int hourOfWeek);

    // Cold start: baselines de outros dispositivos no mesmo slot, com histórico suficiente.
    List<UserHourBaselineDocument> findByHourOfWeekAndCountGreaterThanEqual(int hourOfWeek, int minCount,
                                                                            Pageable pageable);
}
