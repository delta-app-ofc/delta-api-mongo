package br.com.delta.delta_api_mongo.common.health;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean(name = "telemetryMongoTemplate") private MongoTemplate mongo;

    @Test
    void returnsUpWhenMongoResponds() throws Exception {
        when(mongo.executeCommand(any(Document.class))).thenReturn(new Document("ok", 1));

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void returnsUnavailableWhenMongoDoesNotRespond() throws Exception {
        when(mongo.executeCommand(any(Document.class))).thenThrow(new IllegalStateException("Mongo unavailable"));

        mvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }
}
