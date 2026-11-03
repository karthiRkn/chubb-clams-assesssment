import { CLAIM_STATUS_LABELS, Claim, ClaimStatus } from '../../models';

/**
 * Why an officer cannot perform an action on this claim right now, or null if they can.
 * Mirrors the backend rules (only the assigned officer acts, and only from the right status) so the UI explains instead of
 * letting the person hit a 409. The backend still enforces them.
 */
export function actionBlockReason(
  claim: Claim,
  officerId: string | null,
  requiredStatus: ClaimStatus,
  action: string,
): string | null {
  if (claim.assignedOfficerId !== officerId) {
    return claim.assignedOfficerId
      ? 'This claim is assigned to another officer.'
      : `Take this claim first. Only the assigned officer can ${action}.`;
  }
  if (claim.status !== requiredStatus) {
    return `You can only ${action} a claim that is "${CLAIM_STATUS_LABELS[requiredStatus]}". This claim is "${CLAIM_STATUS_LABELS[claim.status]}".`;
  }
  return null;
}
