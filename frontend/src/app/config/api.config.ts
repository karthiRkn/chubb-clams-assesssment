import { environment } from '../../environments/environment';

/** Full API prefixes of the three backend services (each already includes /api/v1). */
export const API = {
  user: `${environment.api.user}/api/v1`,
  claim: `${environment.api.claim}/api/v1`,
  assessment: `${environment.api.assessment}/api/v1`,
} as const;

/** Every URL that starts with one of these is a backend call and gets the bearer token. */
export const API_BASES: readonly string[] = Object.values(environment.api);

/** Routes that must be sent without a token (the backend ignores it, and an expired one must not block a login). */
export const PUBLIC_API_PATHS: readonly string[] = ['/auth/login', '/auth/register'];

/**
 * Markets and currencies offered in the claim form. The brief mentions six APAC markets but never names them, and the
 * backend accepts any 2-letter market and 3-letter currency unless `claims.markets.allowed` is configured, so this list is
 * only a convenience: adjust it to the markets the deployment allows.
 */
export const MARKETS: readonly { code: string; name: string }[] = [
  { code: 'SG', name: 'Singapore' },
  { code: 'HK', name: 'Hong Kong' },
  { code: 'MY', name: 'Malaysia' },
  { code: 'TH', name: 'Thailand' },
  { code: 'ID', name: 'Indonesia' },
  { code: 'VN', name: 'Vietnam' },
  { code: 'AU', name: 'Australia' },
  { code: 'NZ', name: 'New Zealand' },
  { code: 'TW', name: 'Taiwan' },
  { code: 'IN', name: 'India' },
];

export const CURRENCIES: readonly string[] = ['SGD', 'HKD', 'MYR', 'THB', 'IDR', 'VND', 'AUD', 'NZD', 'TWD', 'INR', 'USD'];
