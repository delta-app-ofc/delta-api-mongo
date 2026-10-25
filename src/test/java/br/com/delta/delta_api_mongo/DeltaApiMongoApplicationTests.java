package br.com.delta.delta_api_mongo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "delta.mongodb.telemetry.uri=mongodb://localhost:27017/?serverSelectionTimeoutMS=100",
        "delta.mongodb.telemetry.database=delta_test",
        "delta.device-validation.sql-api-base-url=http://localhost:8080",
        "delta.device-validation.service-credential=delta_svc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
class DeltaApiMongoApplicationTests {
    @Autowired private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBean("mongoTemplate", MongoTemplate.class))
                .isSameAs(context.getBean("telemetryMongoTemplate", MongoTemplate.class));
        assertThat(context.getBean(ConsumptionSummaryRepository.class)).isNotNull();
    }
}
