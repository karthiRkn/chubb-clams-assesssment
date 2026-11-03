/**
 * Chart colours. Canvas cannot read CSS variables, so the values live here.
 *
 * Source: the validated reference palette of the dataviz method (light mode). Slots 1-4 were run through
 * `validate_palette.js --mode light` on the chart surface: every gate passes for adjacent pairs (worst adjacent CVD
 * delta-E 9.1, normal-vision 22.9). The one warning is contrast below 3:1 for aqua and yellow on the light surface, which
 * is why every chart ships with a legend AND a table-view twin (the "relief rule").
 *
 * The app is light-only (see the `theme-type: light` in styles.scss), so there is intentionally no dark set here.
 * Slots are assigned by ENTITY, never by rank: a series keeps its colour when others are filtered out.
 */
export const SERIES_COLORS = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100'] as const;

/** Categorical slot used for each entity, so the same thing is the same colour on every chart. */
export const SLOT = {
  submitted: 0,
  underReview: 1,
  moreInfo: 2,
  approved: 3,
  // Decisions chart: two series, slots 1-2.
  decisionApproved: 0,
  decisionRejected: 1,
  // Single-series charts use slot 1 for every bar.
  single: 0,
} as const;

export const CHART_INK = {
  /** Axis category labels, legend and tooltip text. */
  secondary: '#52514e',
  /** Value-axis ticks. */
  muted: '#898781',
  primary: '#0b0b0b',
  surface: '#fcfcfb',
  gridline: '#e1e0d9',
  baseline: '#c3c2b7',
  border: 'rgba(11, 11, 11, 0.10)',
} as const;

export const CHART_FONT = '"Roboto", system-ui, -apple-system, "Segoe UI", sans-serif';
