import { HttpContextToken } from '@angular/common/http';

/** Set on a request whose failure the caller shows itself (for example a wrong password on the login form). */
export const SKIP_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

/** Set on a request that must not drive the global progress bar (quiet background refreshes). */
export const SKIP_LOADING = new HttpContextToken<boolean>(() => false);
