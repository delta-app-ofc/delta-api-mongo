package br.com.delta.delta_api_mongo.common.devicevalidation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonTokenId;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class DeviceIdDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasTokenId(JsonTokenId.ID_STRING)) {
            return context.reportInputMismatch(String.class, "device_id deve ser uma string.");
        }
        return parser.getString();
    }
}
