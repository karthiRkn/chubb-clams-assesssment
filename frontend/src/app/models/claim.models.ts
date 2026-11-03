export type ClaimStatus =
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'MORE_INFO_REQUIRED'
  | 'APPROVED'
  | 'REJECTED'
  | 'SETTLED';

export type ClaimType = 'MOTOR' | 'PROPERTY';

export const CLAIM_STATUSES: readonly ClaimStatus[] = [
  'SUBMITTED',
  'UNDER_REVIEW',
  'MORE_INFO_REQUIRED',
  'APPROVED',
  'REJECTED',
  'SETTLED',
];

export const CLAIM_TYPES: readonly ClaimType[] = ['MOTOR', 'PROPERTY'];

export const CLAIM_STATUS_LABELS: Record<ClaimStatus, string> = {
  SUBMITTED: 'Submitted',
  UNDER_REVIEW: 'Under review',
  MORE_INFO_REQUIRED: 'More info required',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SETTLED: 'Settled',
};

/** REJECTED and SETTLED are final; APPROVED is not (it still has to be settled). */
export const TERMINAL_STATUSES: readonly ClaimStatus[] = ['REJECTED', 'SETTLED'];

/** Full claim, as returned by `POST /claims`, `GET /claims/{claimId}` and `PATCH /claims/{claimId}`. */
export interface Claim {
  /** Internal key. Never used in a URL - always use `claimId`. */
  id: number;
  /** Public reference, e.g. CLM-2026-000101. */
  claimId: string;
  /** Login name of the claimant who submitted it. */
  claimantId: string | null;
  claimantName: string;
  claimantEmail: string;
  policyNumber: string;
  market: string;
  claimType: ClaimType;
  incidentDescription: string;
  /** yyyy-MM-dd */
  incidentDate: string;
  claimedAmount: number;
  /** Null until approved, and null for a rejected claim. */
  approvedAmount: number | null;
  currency: string;
  status: ClaimStatus;
  assignedOfficerId: string | null;
  decisionReason: string | null;
  decidedAt: string | null;
  settledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Row of a claim list (`GET /claims`, `GET /officer/claims`). */
export interface ClaimSummary {
  id: number;
  claimId: string;
  claimantId: string | null;
  claimantName: string;
  policyNumber: string;
  market: string;
  claimType: ClaimType;
  claimedAmount: number;
  approvedAmount: number | null;
  currency: string;
  status: ClaimStatus;
  assignedOfficerId: string | null;
  createdAt: string;
}

/** POST /api/v1/claims (CLAIMANT). The server sets claimId, claimantId and status. */
export interface CreateClaimRequest {
  claimantName: string;
  claimantEmail: string;
  policyNumber: string;
  /** Two upper-case letters, e.g. SG. */
  market: string;
  claimType: ClaimType;
  incidentDescription: string;
  /** yyyy-MM-dd, not in the future. */
  incidentDate: string;
  /** Positive, at most 2 decimals. */
  claimedAmount: number;
  /** Three upper-case letters, e.g. SGD. */
  currency: string;
}

/** PATCH /api/v1/claims/{claimId} - only while SUBMITTED. Send only the fields that change. */
export type UpdateClaimRequest = Partial<
  Pick<
    CreateClaimRequest,
    | 'claimantName'
    | 'claimantEmail'
    | 'policyNumber'
    | 'claimType'
    | 'incidentDescription'
    | 'incidentDate'
    | 'claimedAmount'
  >
>;

/** POST /api/v1/officer/claims/{claimId}/assign */
export interface AssignClaimRequest {
  officerId: string;
}

/** Result of assign, review and information-request calls. */
export interface WorkflowResponse {
  claimId: string;
  status: ClaimStatus;
  assignedOfficerId: string | null;
  updatedAt: string;
}

/** GET /api/v1/claims/{claimId}/history */
export interface ClaimHistoryEntry {
  claimId: string;
  /** Null for the creation entry. */
  fromStatus: ClaimStatus | null;
  toStatus: ClaimStatus;
  changedAt: string;
  actor: string | null;
}

/** GET /api/v1/claims/{claimId}/assignments */
export interface ClaimAssignmentEntry {
  claimId: string;
  previousOfficerId: string | null;
  newOfficerId: string | null;
  changedAt: string;
  actor: string | null;
}

/** GET /api/v1/claims/{claimId}/updates - one line per changed field, free text is only reported as "changed". */
export interface ClaimUpdateEntry {
  claimId: string;
  changedAt: string;
  actor: string | null;
  changes: string[];
}

/** Filters of `GET /claims` and `GET /officer/claims`. `assigned=false` means "nobody has picked it up". */
export interface ClaimListParams {
  status?: ClaimStatus | null;
  market?: string | null;
  claimType?: ClaimType | null;
  assignedOfficerId?: string | null;
  assigned?: boolean | null;
  /** Zero-based. */
  page?: number;
  /** 1-100, default 20. */
  size?: number;
}

/** Policy number rule: 3-50 characters, letters, digits, '/', '_' or '-', starting with a letter or digit. */
export const POLICY_NUMBER_PATTERN = /^[A-Za-z0-9][A-Za-z0-9/_-]{2,49}$/;
export const POLICY_NUMBER_HINT =
  '3-50 characters: letters, digits, "/", "_" or "-", starting with a letter or digit';
