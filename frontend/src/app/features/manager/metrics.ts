import { CLAIM_STATUS_LABELS, CLAIM_STATUSES, ClaimStatus, OfficerEntry } from '../../models';
import { BarSeries } from '../../shared/chart/bar-chart';

/**
 * Statuses that still need work from an officer. APPROVED is included on purpose: an approved claim is open work until it
 * is settled (the backend counts it the same way).
 */
export const OPEN_STATUSES: readonly ClaimStatus[] = ['SUBMITTED', 'UNDER_REVIEW', 'MORE_INFO_REQUIRED', 'APPROVED'];

/** One stacked series per open status. The slot follows the status (entity), so the colours never depend on filtering. */
export function workloadSeries(officers: readonly OfficerEntry[]): BarSeries[] {
  return OPEN_STATUSES.map((status, slot) => ({
    name: CLAIM_STATUS_LABELS[status],
    slot,
    values: officers.map((officer) => officer.openClaimsByStatus[status] ?? 0),
  }));
}

/** Approved vs rejected decisions per officer. `approvedCount` already includes claims that were settled afterwards. */
export function decisionSeries(officers: readonly OfficerEntry[]): BarSeries[] {
  return [
    { name: 'Approved', slot: 0, values: officers.map((o) => o.approvedCount) },
    { name: 'Rejected', slot: 1, values: officers.map((o) => o.rejectedCount) },
  ];
}

/** Counts per status in lifecycle order, with 0 for a status that has no claims. */
export function statusCounts(countsByStatus: Record<string, number>): { labels: string[]; values: number[] } {
  return {
    labels: CLAIM_STATUSES.map((status) => CLAIM_STATUS_LABELS[status]),
    values: CLAIM_STATUSES.map((status) => countsByStatus[status] ?? 0),
  };
}
