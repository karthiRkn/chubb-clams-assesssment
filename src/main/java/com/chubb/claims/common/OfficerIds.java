package com.chubb.claims.common;

/**
 * Officer IDs are free text and are NOT authenticated. The only value rejected is the literal Swagger UI placeholder
 * "string" (any case), which people send by accident; it is not a real officer ID.
 */
public final class OfficerIds {

    public static final String NOT_PLACEHOLDER = "(?i)^(?!string$).*$";
    public static final String PLACEHOLDER_MESSAGE = "must be a real officer ID, not the placeholder \"string\"";

    private OfficerIds() {
    }
}
