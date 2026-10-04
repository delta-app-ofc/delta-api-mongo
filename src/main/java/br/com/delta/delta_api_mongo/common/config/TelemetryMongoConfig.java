package br.com.delta.delta_api_mongo.common.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryMongoProperties.class)
@EnableMongoRepositories(
        basePackages = {
                "br.com.delta.delta_api_mongo.modules.pulse.repository",
                "br.com.delta.delta_api_mongo.modules.consumption.repository",
                "br.com.delta.delta_api_mongo.modules.device_status.repository"
        },
        mongoTemplateRef = "telemetryMongoTemplate"
)
public class TelemetryMongoConfig {

    @Bean(name = "telemetryMongoClient", destroyMethod = "close")
    MongoClient telemetryMongoClient(TelemetryMongoProperties properties) {
        return MongoClients.create(properties.uri());
    }

    @Bean(name = "telemetryMongoDatabaseFactory")
    MongoDatabaseFactory telemetryMongoDatabaseFactory(
            @Qualifier("telemetryMongoClient") MongoClient mongoClient,
            TelemetryMongoProperties properties
    ) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, properties.database());
    }

    @Bean(name = "telemetryMongoTemplate")
    MongoTemplate telemetryMongoTemplate(
            @Qualifier("telemetryMongoDatabaseFactory") MongoDatabaseFactory databaseFactory
    ) {
        return new MongoTemplate(databaseFactory);
    }
}
