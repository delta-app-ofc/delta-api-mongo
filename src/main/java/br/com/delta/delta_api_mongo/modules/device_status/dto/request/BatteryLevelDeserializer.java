package br.com.delta.delta_api_mongo.modules.device_status.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonTokenId;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class BatteryLevelDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasTokenId(JsonTokenId.ID_NUMBER_INT)) {
            return context.reportInputMismatch(Integer.class, "battery_level deve ser um inteiro de 0 a 100.");
        }
        return parser.getIntValue();
    }
}
