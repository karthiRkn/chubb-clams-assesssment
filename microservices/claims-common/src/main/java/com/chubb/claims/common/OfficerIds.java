package com.chubb.claims.common;

/**
 * Validation of an officer ID that arrives in a request body. Who the officer really is does not depend on this field:
 * a claims officer always acts as the user in their token (see {@code AuthenticatedUser#actingOfficerId}), and an
 * administrator acting for an officer must name one. The only value rejected here is the literal Swagger UI placeholder
 * "string" (any case), which people send by accident; it is not a real officer ID.
 */
public final class OfficerIds {

    public static final String NOT_PLACEHOLDER = "(?i)^(?!string$).*$";
    public static final String PLACEHOLDER_MESSAGE = "must be a real officer ID, not the placeholder \"string\"";

    private OfficerIds() {
    }
}
