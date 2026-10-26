package com.chubb.claims.assessment;

import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Works out which officer an action is recorded against, and leaves a trace when that is not the person who made the call.
 *
 * <p>A claims officer always acts as themselves, so history shows who really did it. An administrator may act for an officer
 * (see {@link AuthenticatedUser#actingOfficerId}); the claim's history then names that officer, because the history columns
 * hold an officer ID and nothing more. So that an administrator's action is not anonymous, this service writes one line to its
 * log with the administrator's login name, the action, the claim and the officer. That line is the only record of the
 * administrator's part: it is a log, not a table, so it is only as durable as the log.
 */
public final class ActingAs {

    private static final Logger log = LoggerFactory.getLogger(ActingAs.class);

    private ActingAs() {
    }

    public static String officer(AuthenticatedUser caller, String requestedOfficerId, String action, String claimId) {
        String officerId = caller.actingOfficerId(requestedOfficerId);
        if (!caller.is(Role.CLAIMS_OFFICER)) {
            // "requested": the line is written before the Claim Service answers, so it also records a request that was refused
            log.info("{} '{}' requested '{}' on claim {} as officer '{}'", caller.role(), clean(caller.username()), action,
                    clean(claimId), clean(officerId));
        }
        return officerId;
    }

    /** Names come from a token and a request, so a line break in one must not be able to forge a log line. */
    private static String clean(String text) {
        return text == null ? null : text.replaceAll("\\p{Cntrl}", "?");
    }
}
