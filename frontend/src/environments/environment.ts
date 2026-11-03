/**
 * Development settings.
 *
 * The three backend services are reached through the Angular dev-server proxy (see proxy.conf.json), which maps
 *   /svc/user       -> http://localhost:8185   (User / Auth service)
 *   /svc/claim      -> http://localhost:8181   (Claim service)
 *   /svc/assessment -> http://localhost:8182   (Assessment service)
 * The backend sends no CORS headers, so the browser must never call those ports directly from another origin.
 */
export const environment = {
  production: false,
  api: {
    user: '/svc/user',
    claim: '/svc/claim',
    assessment: '/svc/assessment',
  },
};
