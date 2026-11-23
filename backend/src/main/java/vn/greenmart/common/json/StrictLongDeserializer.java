package vn.greenmart.common.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/** Integer VND amounts and identifiers must retain the exact JSON value sent by the client. */
public final class StrictLongDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            context.reportInputMismatch(Long.class, "Expected an integer JSON number");
        }
        return parser.getLongValue();
    }
}
