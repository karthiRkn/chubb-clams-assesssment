package com.chubb.claims.assessment.information;

import com.chubb.claims.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** A question to the claimant. It refers to its claim by the public {@code claimId}; the claim itself is not stored here. */
@Entity
@Table(name = "information_requests")
public class InformationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "information_request_id", nullable = false, unique = true, updatable = false, length = 32)
    private String informationRequestId;

    @Column(name = "claim_id", nullable = false, updatable = false, length = 32)
    private String claimId;

    @Column(nullable = false, length = 2000)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InformationRequestStatus status;

    @Column(name = "requested_by_officer_id", nullable = false, length = 50)
    private String requestedByOfficerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(length = 4000)
    private String answer;

    @Column(name = "answered_at")
    private Instant answeredAt;

    protected InformationRequest() {
    }

    public InformationRequest(String informationRequestId, String claimId, String question, String requestedByOfficerId) {
        this.informationRequestId = informationRequestId;
        this.claimId = claimId;
        this.question = question;
        this.requestedByOfficerId = requestedByOfficerId;
        this.status = InformationRequestStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void answer(String text) {
        if (status != InformationRequestStatus.OPEN) {
            throw ApiException.conflict("INFORMATION_REQUEST_ALREADY_ANSWERED",
                    "Information request " + informationRequestId + " has already been answered");
        }
        this.answer = text;
        this.answeredAt = Instant.now();
        this.status = InformationRequestStatus.ANSWERED;
    }

    public Long getId() { return id; }
    public String getInformationRequestId() { return informationRequestId; }
    public String getClaimId() { return claimId; }
    public String getQuestion() { return question; }
    public InformationRequestStatus getStatus() { return status; }
    public String getRequestedByOfficerId() { return requestedByOfficerId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getAnswer() { return answer; }
    public Instant getAnsweredAt() { return answeredAt; }
}
