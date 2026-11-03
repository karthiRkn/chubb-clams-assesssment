/**
 * Production settings.
 *
 * A static build has no dev-server proxy, so the web server that hosts the app must route the same three prefixes to the
 * backend services (see README, "Production hosting"). Keeping the prefixes identical to development means the same build
 * works behind any reverse proxy, and the browser only ever talks to its own origin (no CORS needed).
 */
export const environment = {
  production: true,
  api: {
    user: '/svc/user',
    claim: '/svc/claim',
    assessment: '/svc/assessment',
  },
};
