package com.chubb.claims.common.security;

import java.util.List;

/**
 * Implemented once per service as a Spring bean: that service's routes and the roles allowed on each. Anything a
 * service does not list is reachable by ADMIN only (and by nobody who is not signed in).
 */
public interface AccessRules {

    List<AccessRule> rules();
}
