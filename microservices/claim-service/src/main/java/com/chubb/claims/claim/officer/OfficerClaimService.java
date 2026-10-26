package com.chubb.claims.claim.officer;

import com.chubb.claims.claim.ClaimService;
import com.chubb.claims.claim.WorkflowResponse;
import com.chubb.claims.claim.client.UserClient;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.Role;
import org.springframework.stereotype.Service;

/** Assignment: who may give a claim to which officer. */
@Service
public class OfficerClaimService {

    private final ClaimService claimService;
    private final UserClient users;

    public OfficerClaimService(ClaimService claimService, UserClient users) {
        this.claimService = claimService;
        this.users = users;
    }

    /**
     * Rules, in the order they are checked:
     * <ol>
     *   <li>a claims officer may only take a claim for themselves (the queue is "pick up what is unassigned"); giving a claim to
     *       someone else is a manager's job, so naming another officer is 403;</li>
     *   <li>anyone else (a manager, an administrator) may name any officer, who must be an <em>active claims officer</em>: the
     *       User Service is asked, before the claim is locked so no database lock is held across that call; an unknown, inactive
     *       or non-officer name is 400 {@code INVALID_OFFICER}, and an unreachable User Service is 503;</li>
     *   <li>the claim must exist and be visible to the caller (an officer cannot touch a claim assigned to another officer: 404),
     *       and must not already be settled or rejected (409).</li>
     * </ol>
     * Every change of assignee is recorded in the assignment history with the caller as the actor. This method is not
     * transactional itself: the call to the User Service happens first, and only the write is a transaction.
     */
    public WorkflowResponse assign(AuthenticatedUser caller, String claimId, String officerId) {
        String assignee = officerId.trim();
        if (caller.is(Role.CLAIMS_OFFICER)) {
            if (!assignee.equals(caller.username())) {
                throw ApiException.forbidden("ASSIGNMENT_NOT_PERMITTED",
                        "A claims officer can only assign a claim to themselves; a manager assigns it to anyone else");
            }
        } else if (!users.isActiveClaimsOfficer(assignee)) {
            throw ApiException.badRequest("INVALID_OFFICER", assignee + " is not an active claims officer");
        }

        return claimService.assignTo(caller, claimId, assignee);
    }
}
