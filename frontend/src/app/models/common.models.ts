/** Roles a person can hold. (The backend's SERVICE role is for service-to-service calls only and never reaches a browser.) */
export type Role = 'CLAIMANT' | 'CLAIMS_OFFICER' | 'MANAGER' | 'ADMIN';

export const ROLES: readonly Role[] = ['CLAIMANT', 'CLAIMS_OFFICER', 'MANAGER', 'ADMIN'];

export const ROLE_LABELS: Record<Role, string> = {
  CLAIMANT: 'Claimant',
  CLAIMS_OFFICER: 'Claims Officer',
  MANAGER: 'Manager',
  ADMIN: 'Administrator',
};

/** Error body every backend service returns for a 4xx/5xx. */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  /** Stable machine-readable code, e.g. INVALID_CREDENTIALS, CLAIM_NOT_FOUND, INVALID_CLAIM_TRANSITION. */
  code: string;
  message: string;
  path: string;
}

/** Spring-style page used by `GET /claims`, `GET /officer/claims` and `GET /users`. `page` is zero-based. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
