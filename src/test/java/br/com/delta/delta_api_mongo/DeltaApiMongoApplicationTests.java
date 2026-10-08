package br.com.delta.delta_api_mongo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "delta.mongodb.telemetry.uri=mongodb://localhost:27017/?serverSelectionTimeoutMS=100",
        "delta.mongodb.telemetry.database=delta_test",
        "delta.device-auth.sql-api-base-url=http://localhost:8080",
        "delta.device-auth.service-credential=delta_svc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
class DeltaApiMongoApplicationTests {

    @Test
    void contextLoads() {
    }
}
