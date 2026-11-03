/** GET /api/v1/management/summary (Claim Service, MANAGER). Currencies are never added together. */
export interface CurrencyLiability {
  currency: string;
  claimCount: number;
  estimatedLiability: number;
}

export interface ManagementSummary {
  generatedAt: string;
  /** "Simple estimate ... Not an actuarial reserve" - always shown next to the figures. */
  liabilityNote: string;
  outstandingClaimCount: number;
  outstandingLiabilityByCurrency: CurrencyLiability[];
  /** Status name to count. Statuses with no claims may be absent. */
  countsByStatus: Record<string, number>;
}

/** GET /api/v1/management/officers (Claim Service, MANAGER) */
export interface CurrencyTotal {
  currency: string;
  claimCount: number;
  amount: number;
}

export interface OfficerEntry {
  officerId: string;
  openClaimCount: number;
  openClaimsByStatus: Record<string, number>;
  openClaimedAmountByCurrency: CurrencyTotal[];
  /** Approvals plus rejections. */
  decidedCount: number;
  /** Approved, whether still APPROVED or since SETTLED. */
  approvedCount: number;
  settledCount: number;
  rejectedCount: number;
  approvedAmountByCurrency: CurrencyTotal[];
}

export interface OfficerMetrics {
  generatedAt: string;
  note: string;
  officers: OfficerEntry[];
}
