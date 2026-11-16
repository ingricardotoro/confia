package com.confia.catalog.web;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a JSON string and nothing else. The default reader coerces a number, a boolean or an array into text, which for
 * an amount would let {@code 1500.10} travel as a JSON number, and the contract fixes that an amount is always a string
 * (CLAUDE.md, rule 1). Any other token is refused as a malformed body ({@code 400 validation-failed}).
 */
final class StrictTextDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return parser.getString();
    }
}
