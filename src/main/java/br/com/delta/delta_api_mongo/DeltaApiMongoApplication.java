package br.com.delta.delta_api_mongo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;

@SpringBootApplication(exclude = DataMongoAutoConfiguration.class)
public class DeltaApiMongoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeltaApiMongoApplication.class, args);
    }
}
