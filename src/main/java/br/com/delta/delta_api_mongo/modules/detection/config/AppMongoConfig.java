package br.com.delta.delta_api_mongo.modules.detection.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Segunda conexão Mongo (db_delta_app). Só existe com delta.detection.enabled=true ou delta.alerts.enabled=true,
 * assim a API continua subindo sem MONGODB_APP_URI enquanto o detector e a API de alertas estão desligados.
 * O "mongoTemplate" padrão continua sendo o de telemetria.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${delta.detection.enabled:false} or ${delta.alerts.enabled:false}")
@EnableConfigurationProperties(AppMongoProperties.class)
@EnableMongoRepositories(
        basePackages = "br.com.delta.delta_api_mongo.modules.detection.repository",
        mongoTemplateRef = "appMongoTemplate"
)
public class AppMongoConfig {

    @Bean(name = "appMongoClient", destroyMethod = "close")
    MongoClient appMongoClient(AppMongoProperties properties) {
        return MongoClients.create(properties.uri());
    }

    @Bean(name = "appMongoDatabaseFactory")
    MongoDatabaseFactory appMongoDatabaseFactory(
            @Qualifier("appMongoClient") MongoClient mongoClient,
            AppMongoProperties properties
    ) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, properties.database());
    }

    @Bean(name = "appMongoTemplate")
    MongoTemplate appMongoTemplate(
            @Qualifier("appMongoDatabaseFactory") MongoDatabaseFactory databaseFactory
    ) {
        return new MongoTemplate(databaseFactory);
    }
}
