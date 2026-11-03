import { ClaimStatus } from './claim.models';

/*
 * Officer ID fields (officerId, requestedByOfficerId, decidedByOfficerId, settledByOfficerId) are deliberately absent
 * from every request type below. A claims officer is identified by the token, and the backend rejects any other name
 * with 403 OFFICER_IDENTITY_MISMATCH, so the UI never sends one. (Only an ADMIN acting for an officer must name one,
 * and the admin role has no officer screens.)
 */

export type AssessmentKind = 'NOTE' | 'APPROVED' | 'REJECTED' | 'SETTLED';
export type DecisionType = 'APPROVED' | 'REJECTED';
export type InformationRequestStatus = 'OPEN' | 'ANSWERED';

/** POST /api/v1/officer/claims/{claimId}/review (Assessment Service, :8182) */
export interface ReviewRequest {
  /** Optional, max 2000 characters. Blank means no note is recorded. */
  assessmentNotes?: string;
}

/** GET /api/v1/officer/claims/{claimId}/assessments - officers only, never shown to claimants. */
export interface AssessmentEntry {
  claimId: string;
  officerId: string;
  kind: AssessmentKind;
  notes: string | null;
  createdAt: string;
}

/**
 * POST /api/v1/officer/claims/{claimId}/decision
 * An approval needs a positive `approvedAmount` not above the claimed amount; a rejection must carry none.
 * `currency` must equal the claim's currency.
 */
export interface DecisionRequest {
  decision: DecisionType;
  /** Required, max 2000 characters. */
  decisionReason: string;
  approvedAmount?: number | null;
  currency: string;
}

export interface DecisionResponse {
  claimId: string;
  status: ClaimStatus;
  decisionReason: string;
  approvedAmount: number | null;
  currency: string;
  decidedByOfficerId: string;
  decidedAt: string;
  updatedAt: string;
}

/** POST /api/v1/officer/claims/{claimId}/settlement - APPROVED to SETTLED. */
export interface SettlementRequest {
  /** Optional, max 2000 characters. Kept in the assessment history. */
  settlementNote?: string;
}

export interface SettlementResponse {
  claimId: string;
  status: ClaimStatus;
  approvedAmount: number | null;
  currency: string;
  settledByOfficerId: string;
  settledAt: string;
  updatedAt: string;
}

/** POST /api/v1/officer/claims/{claimId}/information-requests - UNDER_REVIEW to MORE_INFO_REQUIRED. */
export interface CreateInformationRequest {
  /** Required, max 2000 characters. */
  question: string;
}

/** POST /api/v1/claims/{claimId}/information (CLAIMANT) - text only, there is no document upload. */
export interface SubmitInformationRequest {
  informationRequestId: string;
  /** Required, max 4000 characters. */
  answer: string;
}

/** GET /api/v1/claims/{claimId}/information-requests */
export interface InformationRequest {
  /** e.g. IR-2026-000001 */
  informationRequestId: string;
  claimId: string;
  question: string;
  status: InformationRequestStatus;
  requestedByOfficerId: string;
  createdAt: string;
  answer: string | null;
  answeredAt: string | null;
}

export interface AnswerResponse {
  informationRequestId: string;
  claimId: string;
  status: InformationRequestStatus;
  answer: string;
  answeredAt: string;
}

export const MAX_NOTE_LENGTH = 2000;
export const MAX_ANSWER_LENGTH = 4000;
