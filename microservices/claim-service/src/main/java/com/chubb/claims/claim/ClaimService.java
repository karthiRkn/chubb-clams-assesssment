package com.chubb.claims.claim;

import com.chubb.claims.claim.officer.OfficerDtos.ClaimSummary;
import com.chubb.claims.claim.officer.OfficerDtos.PageResponse;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.ClaimVisibility;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

    /** History actor for the claimant's step when the Assessment Service does not say who the claimant was. */
    public static final String CLAIMANT = "CLAIMANT";

    private final ClaimRepository claims;
    private final ClaimStatusHistoryRepository history;
    private final ClaimAssignmentHistoryRepository assignmentHistory;
    private final ClaimUpdateHistoryRepository updateHistory;
    private final ReferenceGenerator references;
    private final MarketPolicy markets;

    public ClaimService(ClaimRepository claims, ClaimStatusHistoryRepository history,
                        ClaimAssignmentHistoryRepository assignmentHistory, ClaimUpdateHistoryRepository updateHistory,
                        ReferenceGenerator references, MarketPolicy markets) {
        this.claims = claims;
        this.history = history;
        this.assignmentHistory = assignmentHistory;
        this.updateHistory = updateHistory;
        this.references = references;
        this.markets = markets;
    }

    /** The claim is recorded as submitted by the caller, whose username is what a claimant is later matched against. */
    @Transactional
    public ClaimResponse create(CreateClaimRequest r, AuthenticatedUser caller) {
        markets.requireAllowed(r.market());
        Claim claim = new Claim(references.nextClaimId(), caller.username(), r.claimantName().trim(), r.claimantEmail().trim(),
                r.policyNumber().trim(), r.market(), r.claimType(), r.incidentDescription().trim(), r.incidentDate(),
                r.claimedAmount(), r.currency());
        claims.saveAndFlush(claim);
        recordTransition(claim, null, caller.username());
        return ClaimResponse.from(claim);
    }

    @Transactional(readOnly = true)
    public ClaimResponse get(String claimId, AuthenticatedUser caller) {
        return ClaimResponse.from(visibleTo(claimId, caller));
    }

    /**
     * The claims the caller may see (see {@link ClaimVisibility}), filtered, sorted newest first and paged in the database.
     * Nothing is filtered in memory.
     */
    @Transactional(readOnly = true)
    public PageResponse<ClaimSummary> search(AuthenticatedUser caller, ClaimStatus status, String market, ClaimType claimType,
                                             String assignedOfficerId, Boolean assigned, int page, int size) {
        if (Boolean.FALSE.equals(assigned) && assignedOfficerId != null && !assignedOfficerId.isBlank()) {
            throw ApiException.badRequest("CONFLICTING_FILTERS",
                    "assigned=false cannot be combined with assignedOfficerId, because an unassigned claim has no officer");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.from(claims.findAll(ClaimSpecifications.filter(status, market, claimType, assignedOfficerId, assigned)
                .and(ClaimSpecifications.visibleTo(caller)), pageable).map(ClaimSummary::from));
    }

    @Transactional(readOnly = true)
    public List<ClaimHistoryResponse> history(String claimId, AuthenticatedUser caller) {
        Claim claim = visibleTo(claimId, caller);
        return history.findAllForClaim(claim).stream().map(ClaimHistoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimAssignmentResponse> assignments(String claimId, AuthenticatedUser caller) {
        Claim claim = visibleTo(claimId, caller);
        return assignmentHistory.findAllForClaim(claim).stream().map(ClaimAssignmentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimUpdateResponse> updates(String claimId, AuthenticatedUser caller) {
        Claim claim = visibleTo(claimId, caller);
        return updateHistory.findAllForClaim(claim).stream().map(ClaimUpdateResponse::from).toList();
    }

    /**
     * A claimant corrects their own claim while it is still SUBMITTED. The claim is row-locked first, so an officer starting
     * review at the same moment and this edit cannot interleave: whichever commits second sees the other's result (the edit
     * then gets 409 {@code CLAIM_NOT_EDITABLE}). Each edit that changes something appends a line to the edit history.
     */
    @Transactional
    public ClaimResponse update(String claimId, UpdateClaimRequest r, AuthenticatedUser caller) {
        if (r.isEmpty()) {
            throw ApiException.badRequest("NO_FIELDS_TO_UPDATE", "The request must change at least one field");
        }
        Claim claim = loadForUpdate(claimId);
        ClaimVisibility.require(caller, claimId, claim.getClaimantId(), claim.getAssignedOfficerId());
        List<String> changes = claim.updateDetails(trimmed(r.claimantName()), trimmed(r.claimantEmail()), trimmed(r.policyNumber()),
                r.claimType(), trimmed(r.incidentDescription()), r.incidentDate(), r.claimedAmount());
        if (!changes.isEmpty()) {
            claims.saveAndFlush(claim);
            updateHistory.save(new ClaimUpdateHistory(claim, caller.username(), String.join("\n", changes)));
        }
        return ClaimResponse.from(claim);
    }

    /**
     * The write of an assignment, once the caller's right to name this officer has been checked (see OfficerClaimService).
     * The claim is row-locked, must be visible to the caller (a claims officer cannot touch another officer's claim) and not
     * be settled or rejected; the change of assignee is recorded with the caller as the actor.
     */
    @Transactional
    public WorkflowResponse assignTo(AuthenticatedUser caller, String claimId, String assignee) {
        Claim claim = loadForUpdate(claimId);
        ClaimVisibility.require(caller, claimId, claim.getClaimantId(), claim.getAssignedOfficerId());
        String previous = claim.getAssignedOfficerId();
        claim.assign(assignee);
        recordAssignment(claim, previous, caller.username());
        return WorkflowResponse.from(claims.saveAndFlush(claim));
    }

    /** For the other services, which have already authenticated the person: status and owner, with no claimant data. */
    @Transactional(readOnly = true)
    public Claim getForService(String claimId) {
        return claims.findByClaimId(claimId).orElseThrow(() -> notFound(claimId));
    }

    /** What {@link ClaimVisibility} says: a claim the caller may not see answers exactly as one that does not exist. */
    private Claim visibleTo(String claimId, AuthenticatedUser caller) {
        Claim claim = claims.findByClaimId(claimId).orElseThrow(() -> notFound(claimId));
        ClaimVisibility.require(caller, claimId, claim.getClaimantId(), claim.getAssignedOfficerId());
        return claim;
    }

    /** Loads and row-locks a claim by public claimId; used by every state-changing workflow. */
    public Claim loadForUpdate(String claimId) {
        return claims.findByClaimIdForUpdate(claimId).orElseThrow(() -> notFound(claimId));
    }

    /**
     * Appends a history row when the claim's status differs from {@code from}. Call it inside the same transaction as
     * the transition, after the claim has been changed, so the change and its history commit or roll back together.
     */
    public void recordTransition(Claim claim, ClaimStatus from, String actor) {
        if (from != claim.getStatus()) {
            history.save(new ClaimStatusHistory(claim, from, claim.getStatus(), actor));
        }
    }

    /**
     * Appends an assignment-history row when the assignee differs from {@code previousOfficerId}. Same transaction as the
     * change, called after the claim has been changed. {@code actor} is who made the change (the caller's login name).
     */
    public void recordAssignment(Claim claim, String previousOfficerId, String actor) {
        if (!Objects.equals(previousOfficerId, claim.getAssignedOfficerId())) {
            assignmentHistory.save(new ClaimAssignmentHistory(claim, previousOfficerId, claim.getAssignedOfficerId(), actor));
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static ApiException notFound(String claimId) {
        return ApiException.notFound("CLAIM_NOT_FOUND", "Claim " + claimId + " was not found");
    }
}
