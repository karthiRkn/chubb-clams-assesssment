// UI end-to-end test: plays a claimant, manager, officer and admin through the whole claim lifecycle in a real browser against the live backend.
// Needs: the three backend services running, `npm start` (the proxy), and Chrome (or set E2E_BROWSER=msedge). Creates data in that backend.
// Screenshots go to e2e/screenshots/. Set E2E_HEADED=1 to watch it run.
import { chromium } from 'playwright-core';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const BASE = process.env.E2E_BASE ?? 'http://localhost:4200';
const SHOTS = fileURLToPath(new URL('./screenshots/', import.meta.url));
mkdirSync(SHOTS, { recursive: true });

// Uses the browser already installed on the machine (no download): Chrome by default, or E2E_BROWSER=msedge.
const browser = await chromium.launch({ channel: process.env.E2E_BROWSER ?? 'chrome', headless: !process.env.E2E_HEADED });

const problems = [];       // console errors, page errors, failed API calls
const results = [];        // step outcomes
const note = (who, what) => problems.push(`[${who}] ${what}`);

async function newPage(who, viewport = { width: 1366, height: 900 }) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type() === 'error') note(who, `console.error: ${m.text().slice(0, 300)}`); });
  page.on('pageerror', (e) => note(who, `pageerror: ${String(e).slice(0, 300)}`));
  page.on('response', (r) => {
    const u = r.url();
    if (u.includes('/svc/') && r.status() >= 400) note(who, `HTTP ${r.status()} ${r.request().method()} ${u.replace(BASE, '')}`);
  });
  return page;
}

async function step(name, fn) {
  try { const v = await fn(); results.push({ name, ok: true }); console.log(`  PASS  ${name}`); return v; }
  catch (e) {
    const lines = String(e.message ?? e).split('\n').filter((l) => l.trim());
    results.push({ name, ok: false, err: lines[0] });
    console.log(`  FAIL  ${name}\n        ${lines.slice(0, 12).join('\n        ')}`);
    return undefined;
  }
}
const shot = (page, name) => page.screenshot({ path: `${SHOTS}${name}.png` });

async function login(page, username) {
  await page.goto(`${BASE}/login`);
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password', { exact: true }).fill('password123');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
}
const confirmDialog = async (page, button) => {
  const dialog = page.getByRole('dialog');
  await dialog.waitFor();
  await dialog.getByRole('button', { name: button, exact: true }).click();
  await dialog.waitFor({ state: 'detached' });
};
const pick = async (page, label, option) => {
  // Required selects expose their name as "Market *", so match by substring. An empty select has Material's floating label
  // resting on top of it, which a real click lands on (and opens the select) but Playwright's actionability check rejects.
  const select = page.getByLabel(label);
  await select.click({ force: true });
  await page.getByRole('listbox').waitFor();            // the panel really opened
  await page.getByRole('option', { name: option }).first().click();
  await page.getByRole('listbox').waitFor({ state: 'detached' });   // and closed again, so it cannot cover the next field
};
const d = new Date(); d.setDate(d.getDate() - 2);
const usDate = `${d.getMonth() + 1}/${d.getDate()}/${d.getFullYear()}`;

// ------------------------------------------------------------------ sign in / guards
console.log('\n[A] Sign-in page and route security');
const anon = await newPage('anon');
await step('signed-out visit to /officer/queue -> /login?returnUrl=%2Fofficer%2Fqueue', async () => {
  await anon.goto(`${BASE}/officer/queue`);
  await anon.waitForURL(/\/login\?returnUrl=%2Fofficer%2Fqueue/);
});
await step('login page renders', async () => { await anon.getByRole('heading', { name: 'Sign in' }).waitFor(); await shot(anon, '01-login'); });
await step('wrong password shows an inline error (no redirect, no toast)', async () => {
  await anon.getByLabel('Username').fill('claimant1');
  await anon.getByLabel('Password', { exact: true }).fill('nope-nope');
  await anon.getByRole('button', { name: 'Sign in' }).click();
  await anon.getByRole('alert').getByText('Incorrect username or password.').waitFor();
});
await step('empty form shows validation messages', async () => {
  await anon.reload();
  await anon.getByRole('button', { name: 'Sign in' }).click();
  await anon.getByText('Enter your username.').waitFor();
});
await step('after sign-in the returnUrl is honoured (claimant is bounced to own home from an officer URL)', async () => {
  await anon.goto(`${BASE}/officer/queue`);
  await anon.getByLabel('Username').fill('claimant1');
  await anon.getByLabel('Password', { exact: true }).fill('password123');
  await anon.getByRole('button', { name: 'Sign in' }).click();
  await anon.waitForURL('**/claimant/dashboard');
});
await step('wrong-role URL redirects with a notice', async () => {
  await anon.goto(`${BASE}/manager/dashboard`);
  await anon.waitForURL('**/claimant/dashboard');
  await anon.getByText('You do not have access to that page.').waitFor();
});
await step('sign out returns to login and the token is gone from sessionStorage', async () => {
  await anon.getByRole('button', { name: 'Account menu' }).click();
  await anon.getByRole('menuitem', { name: 'Sign out' }).click();
  await anon.waitForURL('**/login');
  const stored = await anon.evaluate(() => sessionStorage.getItem('chubb.claims.session'));
  if (stored) throw new Error('session still stored');
});
await step('unknown URL when signed in shows the not-found page', async () => {
  await login(anon, 'claimant1');
  await anon.goto(`${BASE}/nope/nothing`);
  await anon.getByRole('heading', { name: 'Page not found' }).waitFor();
});

// ------------------------------------------------------------------ claimant
console.log('\n[B] Claimant');
const cl = await newPage('claimant');
await login(cl, 'claimant1');
await step('dashboard renders with status cards', async () => { await cl.getByRole('heading', { name: /Hello, claimant1/ }).waitFor(); await cl.getByText('Total claims').waitFor(); await cl.getByText('Recent claims').waitFor(); await shot(cl, '02-claimant-dashboard'); });
await step('sidebar shows only claimant items', async () => {
  const nav = cl.getByRole('navigation', { name: 'Main navigation' });
  for (const t of ['Dashboard', 'New claim', 'My claims', 'Information requests', 'Profile']) await nav.getByText(t, { exact: true }).waitFor();
  if (await nav.getByText('Claims queue').count()) throw new Error('officer item visible to claimant');
});
await step('create-claim form blocks an empty submit and names the fields', async () => {
  await cl.goto(`${BASE}/claimant/new-claim`);
  await cl.getByRole('button', { name: 'Submit claim' }).click();
  await cl.getByText('Enter your policy number.').waitFor();
  await cl.getByText('Choose the date it happened.').waitFor();
  // Only "New claim" may be highlighted in the sidebar, not "My claims" as well.
  const active = await cl.getByRole('navigation', { name: 'Main navigation' }).locator('[aria-current="page"]').allInnerTexts();
  if (active.length !== 1 || !/New claim/.test(active[0])) throw new Error(`sidebar highlights: ${JSON.stringify(active)}`);
  await cl.waitForTimeout(500);
  // The policy hint wraps to two lines on this width; it must push the next heading down, not sit on top of it.
  const overlap = await cl.evaluate(() => {
    const hint = [...document.querySelectorAll('mat-hint, mat-error')].find((e) => /3-50 characters/.test(e.textContent));
    const heading = [...document.querySelectorAll('h2')].find((h) => /About the incident/.test(h.textContent));
    return hint && heading ? Math.round(hint.getBoundingClientRect().bottom - heading.getBoundingClientRect().top) : null;
  });
  if (overlap !== null && overlap > 0) throw new Error(`policy hint overlaps the next heading by ${overlap}px`);
  await shot(cl, '03-create-claim-errors');
});
const claimId = await step('create a claim through the form', async () => {
  await cl.getByLabel('Full name').fill('Mei Tan');
  await cl.getByLabel('Email').fill('mei.tan@example.com');
  await cl.getByLabel('Policy number').fill('POL-2026-0042');
  await pick(cl, 'Market', /SG/);
  await cl.getByLabel('Date of incident').fill(usDate);
  await cl.getByLabel('Amount claimed').fill('2400.50');
  await pick(cl, 'Currency', 'SGD');
  await cl.getByLabel('What happened?').fill('Rear bumper damaged in a low-speed collision at a car park.');
  await shot(cl, '04-create-claim-filled');
  await cl.getByRole('button', { name: 'Submit claim' }).click();
  await cl.getByRole('dialog').waitFor();
  await cl.waitForTimeout(500);
  await shot(cl, '04b-confirm-dialog');
  await confirmDialog(cl, 'Submit claim');
  await cl.waitForURL(/\/claimant\/claims\/CLM-/);
  return cl.url().split('/').pop();
});
console.log(`        claim: ${claimId}`);
if (!claimId) {
  console.log('\nABORT: the claim was not created, so every later step would only cascade. Fix this first.');
  await shot(cl, '99-abort-create-claim');
  await browser.close();
  process.exit(2);
}
await step('claim details: banner, details, progress tracker and the three history tabs', async () => {
  await cl.getByRole('heading', { name: claimId }).waitFor();
  await cl.getByText('waiting for an officer to pick it up').waitFor();
  await cl.getByRole('list', { name: 'Claim progress' }).waitFor();
  for (const tab of ['Status history', 'Assignment history', 'Update history']) await cl.getByRole('tab', { name: tab }).waitFor();
  await cl.getByText('Claim submitted').waitFor();                         // first tab: the status timeline
  await cl.getByRole('tab', { name: 'Assignment history' }).click();
  await cl.getByText('has not been assigned to an officer yet').waitFor();
  await cl.getByRole('tab', { name: 'Status history' }).click();
  await cl.getByText(/SGD\s2,400\.50/).first().waitFor();                 // currency code, a space, then the amount
  if (await cl.getByText('â€”').count()) throw new Error('garbled em dash on the page');
  await shot(cl, '05-claimant-claim-details');
});
await step('Claim Edit page (only while SUBMITTED): save is disabled until something changes', async () => {
  await cl.getByRole('link', { name: 'Edit claim' }).click();
  await cl.waitForURL(new RegExp(`/claimant/claims/${claimId}/edit$`));
  await cl.getByLabel('What happened?').waitFor();
  if (!(await cl.getByRole('button', { name: 'Save changes' }).isDisabled())) throw new Error('Save is enabled with no change');
  await cl.getByLabel('What happened?').fill('Rear bumper and tail light damaged in a low-speed collision at a car park.');
  await shot(cl, '05b-claimant-edit-claim');
  await cl.getByRole('button', { name: 'Save changes' }).click();
  await cl.waitForURL(new RegExp(`/claimant/claims/${claimId}$`));
  await cl.getByRole('tab', { name: 'Update history' }).click();
  await cl.getByText('Details edited').waitFor();
});
await step('my claims lists it, filters work, empty filter shows the empty state', async () => {
  await cl.goto(`${BASE}/claimant/claims`);
  await cl.getByRole('link', { name: claimId }).waitFor();
  await pick(cl, 'Status', 'Approved');
  await cl.getByText('No claims match these filters').waitFor();
  await cl.getByRole('button', { name: 'Clear filters' }).first().click();
  await cl.getByRole('link', { name: claimId }).waitFor();
  await shot(cl, '06-my-claims');
});
await step('information requests page: empty state', async () => { await cl.goto(`${BASE}/claimant/information-requests`); await cl.getByText('Nothing is waiting on you').waitFor(); });
await step('profile page', async () => { await cl.goto(`${BASE}/profile`); await cl.getByRole('heading', { name: 'Profile' }).waitFor(); await cl.getByText('claimant1').first().waitFor(); });

// ------------------------------------------------------------------ manager assigns
console.log('\n[C] Manager');
const mg = await newPage('manager');
await login(mg, 'manager1');
await step('manager dashboard: KPI tiles, liability tile, both charts', async () => {
  await mg.getByRole('heading', { name: 'Overview' }).waitFor();
  await mg.getByText('Outstanding claims').first().waitFor();
  await mg.getByText('Claim count').waitFor();
  await mg.getByText('Estimated liability by currency').waitFor();
  await mg.getByText(/[A-Z]{3}\s[\d,]+\.\d{2}/).first().waitFor();       // e.g. "HKD 18,000.00"
  await mg.getByRole('img', { name: /number of claims in each status/ }).waitFor();
  await mg.waitForTimeout(800); // let the chart animate
  await shot(mg, '07-manager-dashboard');
});
await step('assign the claim through the dialog', async () => {
  await mg.goto(`${BASE}/manager/assignments`);
  const row = mg.locator('tr', { hasText: claimId });
  await row.getByRole('button', { name: 'Assign' }).click();
  const dialog = mg.getByRole('dialog');
  await dialog.getByRole('button', { name: /officer1/ }).click();     // one-click chip from the workload report, with its open count
  if ((await dialog.getByLabel('Claims officer (login name)').inputValue()) !== 'officer1') throw new Error('chip did not fill the field');
  await mg.waitForTimeout(500);
  await shot(mg, '08-assign-dialog');
  // nothing may cover the action buttons
  const covered = await mg.evaluate(() => { const b = [...document.querySelectorAll('mat-dialog-actions button')].find((x) => x.textContent.trim() === 'Assign'); const r = b.getBoundingClientRect(); const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2); return !b.contains(hit); });
  if (covered) throw new Error('something covers the Assign button');
  await dialog.getByRole('button', { name: 'Assign', exact: true }).click();
  await dialog.waitFor({ state: 'detached' });
  await mg.getByText(`${claimId} is now assigned to officer1.`).waitFor();
});
await step('a non-officer name is refused by the server and the dialog stays open', async () => {
  const row = mg.locator('tr', { hasText: claimId });
  await mg.getByRole('tab', { name: 'Assigned', exact: true }).click();
  await mg.locator('tr', { hasText: claimId }).getByRole('button', { name: 'Reassign' }).click();
  const dialog = mg.getByRole('dialog');
  await dialog.getByLabel('Claims officer (login name)').fill('claimant1');
  await dialog.getByRole('button', { name: 'Reassign', exact: true }).click();
  await mg.locator('.toast-error').waitFor();                      // the backend's INVALID_OFFICER message, as a toast
  console.log('        toast says:', (await mg.locator('.toast-error').innerText()).replace(/\s+/g, ' ').slice(0, 120));
  if (!(await dialog.isVisible())) throw new Error('dialog closed on an error');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
});
await step('Manager > Claims lists every claim (GET /claims) and opens the read-only overview', async () => {
  await mg.getByRole('navigation', { name: 'Main navigation' }).getByText('Claims', { exact: true }).click();
  await mg.waitForURL('**/manager/claims');
  await mg.getByRole('link', { name: claimId }).waitFor();
  await shot(mg, '08b-manager-claims');
  await mg.getByRole('link', { name: claimId }).click();
  await mg.waitForURL(new RegExp(`/manager/claims/${claimId}$`));
  await mg.getByRole('heading', { name: claimId }).waitFor();
  await mg.getByRole('tab', { name: 'Assignment history' }).click();
  await mg.getByText('officer1', { exact: true }).first().waitFor();
  await mg.getByText(/by manager1/).waitFor();
});

// ------------------------------------------------------------------ officer
console.log('\n[D] Claims officer');
const of = await newPage('officer');
await login(of, 'officer1');
await step('officer dashboard', async () => { await of.getByRole('heading', { name: /Hello, officer1/ }).waitFor(); await of.getByText('Ready to settle').waitFor(); await shot(of, '09-officer-dashboard'); });
await step('assigned claims lists the claim; the dashboard-style ?status= deep link pre-filters', async () => {
  await of.goto(`${BASE}/officer/assigned?status=SUBMITTED`);
  await of.getByRole('link', { name: claimId }).waitFor();
  await of.getByRole('combobox', { name: 'Status' }).getByText('Submitted').waitFor();
});
const noDecisionUi = async (page, why) => {
  if (await page.getByRole('heading', { name: 'Review actions', exact: true }).count()) throw new Error(`decision panel shown ${why}`);
  if (await page.getByRole('button', { name: 'Request Information' }).count()) throw new Error(`Request Information offered ${why}`);
};
const IR_TABLE_HEADERS = ['Request ID', 'Question', 'Status', 'Created', 'Answer', 'Answered'];
await step('SUBMITTED: only Start review (no Request Information, Approve or Reject)', async () => {
  await of.getByRole('link', { name: claimId }).click();
  await of.getByRole('heading', { name: 'Start review' }).waitFor();
  await noDecisionUi(of, 'before the review started');
  await of.getByRole('radio', { name: 'Approve' }).count().then((n) => { if (n) throw new Error('Approve offered before the review started'); });
  await shot(of, '10-officer-review-start');
});
await step('Information Requests section: the six-column table with its empty state, below the review section', async () => {
  await of.getByRole('heading', { name: 'Information Requests', exact: true }).waitFor();
  await of.getByText('No information requests').waitFor();
  const below = await of.evaluate(() => {
    const top = (re) => [...document.querySelectorAll('h2')].find((h) => re.test(h.textContent))?.getBoundingClientRect().top ?? null;
    return { review: top(/^\s*Start review\s*$/), info: top(/Information Requests/) };
  });
  if (below.review === null || below.info === null || below.info <= below.review) throw new Error(`Information Requests is not below the review section: ${JSON.stringify(below)}`);
});
await step('Start review -> UNDER_REVIEW: Approve, Reject and Request Information appear together', async () => {
  await of.getByLabel('Assessment note (optional)').fill('Photos received; damage looks consistent.');
  await of.getByRole('button', { name: 'Start review' }).last().click();
  await confirmDialog(of, 'Start review');
  await of.getByText('Review of ' + claimId + ' has started.').waitFor();
  await of.getByRole('heading', { name: 'Review actions', exact: true }).waitFor();
  // one row: the three actions share a line (not scattered between the header and the form)
  const centres = await Promise.all([
    of.getByRole('radio', { name: 'Approve' }),
    of.getByRole('radio', { name: 'Reject' }),
    of.getByRole('button', { name: 'Request Information' }),
  ].map(async (l) => { const b = await l.boundingBox(); if (!b) throw new Error('an action is not visible'); return b.y + b.height / 2; }));
  if (Math.max(...centres) - Math.min(...centres) > 12) throw new Error(`actions are not on one row: ${centres.map(Math.round)}`);
  await of.getByRole('tab', { name: 'Status history' }).waitFor();
  await shot(of, '10b-review-actions');
});
await step('Request Information dialog: title, field, placeholder, buttons and validation (required, 2000 max)', async () => {
  await of.getByRole('button', { name: 'Request Information' }).click();
  const dialog = of.getByRole('dialog');
  await dialog.getByRole('heading', { name: 'Request Additional Information' }).waitFor();
  const box = dialog.getByLabel('Question');
  if ((await box.getAttribute('placeholder')) !== 'Please describe what additional information is required.') throw new Error('placeholder differs');
  await dialog.getByRole('button', { name: 'Cancel' }).waitFor();
  await dialog.getByRole('button', { name: 'Send Request' }).click();                      // required
  await dialog.getByText('Question is required.').waitFor();
  await box.fill('x'.repeat(2001));                                                         // too long: a message, not silent truncation
  if ((await box.inputValue()).length !== 2001) throw new Error('the text was silently truncated');
  await dialog.getByRole('button', { name: 'Send Request' }).click();
  await dialog.getByText('Question must be 2000 characters or fewer.').waitFor();
  await of.waitForTimeout(300);
  await shot(of, '11a-request-dialog-validation');
  await box.fill('');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  await dialog.waitFor({ state: 'detached' });
});
await step('Request Information dialog: every backend failure is explained inside the dialog (400, 403, 404, 409, 503)', async () => {
  const expectations = [
    { status: 400, body: { code: 'VALIDATION_FAILED', message: 'question: size must be between 1 and 2000' }, where: 'field', text: 'question: size must be between 1 and 2000', blocks: false },
    { status: 403, body: { code: 'FORBIDDEN', message: 'raw' }, where: 'banner', text: 'Access denied. You do not have permission to request information on this claim.', blocks: true },
    { status: 404, body: { code: 'CLAIM_NOT_FOUND', message: 'raw' }, where: 'banner', text: 'Claim not found. It may have been removed, or it is assigned to another officer.', blocks: true },
    { status: 409, body: { code: 'INVALID_CLAIM_TRANSITION', message: 'Claim is not under review.' }, where: 'banner', text: 'Claim is not under review.', blocks: true },
    { status: 503, body: { code: 'DOWNSTREAM_UNAVAILABLE', message: 'raw' }, where: 'banner', text: 'The service is temporarily unavailable. Please try again in a few moments.', blocks: false },
  ];
  for (const e of expectations) {
    await of.route('**/officer/claims/*/information-requests', (route) =>
      route.request().method() === 'POST'
        ? route.fulfill({ status: e.status, contentType: 'application/json', body: JSON.stringify({ timestamp: '', status: e.status, error: '', path: '', ...e.body }) })
        : route.continue());
    await of.getByRole('button', { name: 'Request Information' }).click();
    const dialog = of.getByRole('dialog');
    await dialog.getByLabel('Question').fill('Please send the repair estimate.');
    await dialog.getByRole('button', { name: 'Send Request' }).click();
    const place = e.where === 'field' ? dialog.locator('mat-error') : dialog.getByRole('alert');
    await place.getByText(e.text, { exact: true }).waitFor();
    if (await of.locator('.toast-error').count()) throw new Error(`${e.status}: a toast was shown as well as the inline message`);
    const disabled = await dialog.getByRole('button', { name: 'Send Request' }).isDisabled();
    if (disabled !== e.blocks) throw new Error(`${e.status}: Send Request should be ${e.blocks ? 'disabled' : 'enabled'}`);
    if (e.status === 503) await shot(of, '11b-request-dialog-error');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await dialog.waitFor({ state: 'detached' });
    await of.unroute('**/officer/claims/*/information-requests');
    await of.getByRole('heading', { name: 'Review actions', exact: true }).waitFor();    // still UNDER_REVIEW: the real claim did not change
  }
});
await step('Request Information succeeds: toast, claim becomes MORE_INFO_REQUIRED, the table refreshes', async () => {
  await of.getByRole('button', { name: 'Request Information' }).click();
  const dialog = of.getByRole('dialog');
  await dialog.getByRole('button', { name: /Please send photos of the damage\./ }).click();   // a quick prompt fills the box
  await dialog.getByLabel('Question').fill('Please send the repair estimate and the date it was issued.');
  await of.waitForTimeout(400);
  await shot(of, '11-request-information-dialog');
  await dialog.getByRole('button', { name: 'Send Request' }).click();
  await dialog.waitFor({ state: 'detached' });
  await of.getByText('Information request sent successfully').waitFor();
  await of.getByText(/Waiting for the claimant to answer 1 request/).waitFor();          // the claim was reloaded
  await of.getByText('More info required').first().waitFor();                              // status chip
  const table = of.getByRole('table', { name: 'Information requests' });
  const headers = (await table.getByRole('columnheader').allInnerTexts()).map((h) => h.trim());
  if (JSON.stringify(headers) !== JSON.stringify(IR_TABLE_HEADERS)) throw new Error(`columns: ${JSON.stringify(headers)}`);
  const row = table.locator('tbody tr').first();
  await row.getByText(/^IR-\d{4}-\d{6}$/).waitFor();
  await row.getByText('Please send the repair estimate and the date it was issued.').waitFor();
  await row.locator('.state').filter({ hasText: /Open/ }).waitFor();
  await row.getByText('Awaiting answer').waitFor();
  await of.getByRole('heading', { name: 'Information Requests', exact: true }).scrollIntoViewIfNeeded();
  await shot(of, '11c-information-requests-table-open');
});
await step('MORE_INFO_REQUIRED: waiting banner, and no Approve / Reject / Request Information', async () => {
  await noDecisionUi(of, 'while waiting for information');
  await of.getByRole('radio', { name: 'Approve' }).count().then((n) => { if (n) throw new Error('Approve offered while waiting for information'); });
  await shot(of, '12-officer-waiting');
});

// ------------------------------------------------------------------ claimant answers
console.log('\n[E] Claimant answers');
await step('claimant sees the notification and answers on the information requests page', async () => {
  await cl.goto(`${BASE}/claimant/dashboard`);
  await cl.getByText('Need your information').waitFor();
  await cl.getByRole('button', { name: /Notifications/ }).click();
  await cl.getByText(/needs more information from you/).waitFor();
  await cl.keyboard.press('Escape');
  await cl.goto(`${BASE}/claimant/information-requests`);
  await cl.getByText('Please send the repair estimate').waitFor();
  await shot(cl, '13-claimant-information-request');
  await cl.getByLabel('Your answer').fill('The garage estimate is 1,800 SGD, issued on 14 October.');
  await cl.getByRole('button', { name: 'Send answer' }).click();
  await confirmDialog(cl, 'Send answer');
  await cl.getByText('Your answer was sent to the claims officer.').waitFor();
  await cl.getByText('Nothing is waiting on you').waitFor();
});
await step('claimant claim page now shows the answered request and the claim back under review', async () => {
  await cl.goto(`${BASE}/claimant/claims/${claimId}`);
  await cl.getByText('An officer is reviewing your claim.').waitFor();
  await cl.getByText('The garage estimate is 1,800 SGD').waitFor();
});
await step('the Edit page now refuses (claim is no longer SUBMITTED) and the Edit button is gone', async () => {
  if (await cl.getByRole('link', { name: 'Edit claim' }).count()) throw new Error('Edit claim offered after review started');
  await cl.goto(`${BASE}/claimant/claims/${claimId}/edit`);
  await cl.getByText('This claim can no longer be edited').waitFor();
});

await step('claimant answered -> UNDER_REVIEW again: the table shows the answer and all three actions return', async () => {
  await of.goto(`${BASE}/officer/claims/${claimId}`);
  const row = of.getByRole('table', { name: 'Information requests' }).locator('tbody tr').first();
  await row.locator('.state').filter({ hasText: /Answered/ }).waitFor();
  await row.getByText('The garage estimate is 1,800 SGD, issued on 14 October.').waitFor();
  if ((await row.locator('td').last().innerText()).trim() === '—') throw new Error('the answered date is missing');
  await of.getByRole('heading', { name: 'Review actions', exact: true }).waitFor();
  await of.getByRole('radio', { name: 'Approve' }).waitFor();
  await of.getByRole('radio', { name: 'Reject' }).waitFor();
  await of.getByRole('button', { name: 'Request Information' }).waitFor();
  await of.getByRole('heading', { name: 'Information Requests', exact: true }).scrollIntoViewIfNeeded();
  await shot(of, '12b-officer-answered');
});
await step('Manager sees the same six-column table, read-only (no actions)', async () => {
  await mg.goto(`${BASE}/manager/claims/${claimId}`);
  await mg.getByRole('heading', { name: 'Information Requests', exact: true }).waitFor();
  const headers = (await mg.getByRole('table', { name: 'Information requests' }).getByRole('columnheader').allInnerTexts()).map((h) => h.trim());
  if (JSON.stringify(headers) !== JSON.stringify(IR_TABLE_HEADERS)) throw new Error(`columns: ${JSON.stringify(headers)}`);
  if (await mg.getByRole('button', { name: 'Request Information' }).count()) throw new Error('a manager was offered Request Information');
});

// ------------------------------------------------------------------ officer decides and settles
console.log('\n[F] Officer approves and settles');
await step('approve inline on the workspace: amount above the claim is blocked, a valid amount approves', async () => {
  await of.goto(`${BASE}/officer/claims/${claimId}`);
  await of.getByRole('heading', { name: 'Review actions', exact: true }).waitFor();
  const amount = of.getByLabel(/Approved amount/);
  await amount.waitFor();
  if ((await amount.inputValue()) !== '2400.5') throw new Error(`amount should default to the claimed amount, got ${await amount.inputValue()}`);
  await amount.fill('99999');
  await of.getByLabel('Reason for approval').fill('Covered by the policy; estimate accepted.');
  await of.getByRole('button', { name: 'Approve claim' }).click();
  await of.getByText('The amount cannot exceed the claimed amount.').waitFor();
  await amount.fill('1800');
  await shot(of, '14-decision-panel');
  await of.getByRole('button', { name: 'Approve claim' }).click();
  await confirmDialog(of, 'Approve claim');
  await of.getByText(`${claimId} was approved.`).waitFor();
  await of.getByRole('link', { name: 'Settle claim' }).waitFor();          // status APPROVED -> Settlement page is offered
  await noDecisionUi(of, 'after the claim was approved');
});
await step('settle the approved claim', async () => {
  await of.getByRole('link', { name: 'Settle claim' }).click();
  await of.getByLabel('Settlement note (optional)').fill('Paid by bank transfer.');
  await of.getByRole('button', { name: 'Settle claim' }).click();
  await confirmDialog(of, 'Settle claim');
  await of.getByText(`${claimId} was settled.`).waitFor();
});
await step('settled claim: no more actions; history tabs show the full status timeline', async () => {
  if (await of.getByRole('link', { name: 'Settle claim' }).count()) throw new Error('Settle claim still offered');
  await noDecisionUi(of, 'on a settled claim');
  await of.getByText('Review note').first().waitFor();
  await of.getByText('Paid by bank transfer.').waitFor();
  await of.getByRole('tab', { name: 'Status history' }).click();
  for (const status of ['Under review', 'Approved', 'Settled']) await of.getByRole('list', { name: 'Status timeline' }).getByText(status).first().waitFor();
  await shot(of, '15-officer-settled');
});
await step('the Settlement page for a settled claim explains why it is unavailable', async () => {
  await of.goto(`${BASE}/officer/claims/${claimId}/settlement`);
  await of.getByText('Not available right now').waitFor();
});
await step('assessment history pages', async () => {
  await of.goto(`${BASE}/officer/assessments`);
  await of.getByRole('link', { name: claimId }).click();
  await of.getByRole('heading', { name: 'Assessment history' }).waitFor();
  await of.getByText('Approved', { exact: true }).first().waitFor();
});

// second claim: officer takes it from the queue and rejects it (set up through the API)
console.log('\n[G] Queue: take a claim, then reject it');
const claimant2 = await (async () => {
  const lr = await fetch(`${BASE}/svc/user/api/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'claimant1', password: 'password123' }) });
  const { accessToken } = await lr.json();
  const cr = await fetch(`${BASE}/svc/claim/api/v1/claims`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` }, body: JSON.stringify({ claimantName: 'Mei Tan', claimantEmail: 'mei.tan@example.com', policyNumber: 'POL-2026-0099', market: 'HK', claimType: 'PROPERTY', incidentDescription: 'Water damage from a burst pipe.', incidentDate: '2026-10-20', claimedAmount: 9000, currency: 'HKD' }) });
  return (await cr.json()).claimId;
})();
await step('queue shows the new claim and Take claim assigns it', async () => {
  await of.goto(`${BASE}/officer/queue`);
  await of.getByRole('link', { name: claimant2 }).waitFor();
  await shot(of, '16-officer-queue');
  await of.locator('tr', { hasText: claimant2 }).getByRole('button', { name: 'Take claim' }).click();
  await confirmDialog(of, 'Take claim');
  await of.waitForURL(new RegExp(`/officer/claims/${claimant2}$`));
});
await step('start the review of the second claim', async () => {
  await of.getByRole('button', { name: 'Start review' }).last().click();
  await confirmDialog(of, 'Start review');
  await of.getByRole('heading', { name: 'Review actions', exact: true }).waitFor();
});
await step('Request Information with an expired session (401) sends the officer to the login page, without a stray error', async () => {
  const other = await newPage('officer-401');                                // its own session, so the main officer page stays signed in
  await login(other, 'officer1');
  await other.goto(`${BASE}/officer/claims/${claimant2}`);
  await other.getByRole('button', { name: 'Request Information' }).click();
  await other.route('**/officer/claims/*/information-requests', (route) =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ timestamp: '', status: 401, error: '', code: 'UNAUTHENTICATED', message: 'Missing or invalid token', path: '' }) })
      : route.continue());
  const dialog = other.getByRole('dialog');
  await dialog.getByLabel('Question').fill('Please send the repair estimate.');
  await dialog.getByRole('button', { name: 'Send Request' }).click();
  await other.waitForURL(/\/login\?returnUrl=/);
  await other.getByText('Your session has expired. Please sign in again.').first().waitFor();
  if (await other.evaluate(() => sessionStorage.getItem('chubb.claims.session'))) throw new Error('the session was not cleared');
});
await step('reject inline with a reason (the amount is not asked for; final, warns first)', async () => {
  await of.getByRole('radio', { name: 'Reject' }).click();                                    // the Approve | Reject choice (a radio group)
  await of.getByLabel(/Approved amount/).waitFor({ state: 'detached', timeout: 5000 }).catch(() => { throw new Error('an amount is still asked for while rejecting'); });
  await of.getByText('Rejection is final.').waitFor();
  await of.getByRole('button', { name: 'Reject claim' }).click();                              // reason is required
  await of.getByText('Give a reason for the decision.').waitFor();
  await of.getByLabel('Reason for rejection').fill('Water damage from a burst pipe is excluded under this policy.');
  await shot(of, '14b-decision-panel-reject');
  await of.getByRole('button', { name: 'Reject claim' }).click();
  await confirmDialog(of, 'Reject claim');
  await of.getByText(`${claimant2} was rejected.`).waitFor();
  await of.getByText('This claim was rejected').first().waitFor().catch(() => undefined);
  await noDecisionUi(of, 'after the claim was rejected');
});

// ------------------------------------------------------------------ manager reports (now with real data)
console.log('\n[H] Manager reports with data');
await step('officer metrics: stacked chart, decisions chart, table, sort', async () => {
  await mg.goto(`${BASE}/manager/officers`);
  await mg.getByRole('heading', { name: 'Officer metrics' }).waitFor();
  for (const widget of ['Workload', 'Approvals', 'Settlements', 'Rejections']) await mg.getByText(widget, { exact: true }).first().waitFor();
  await mg.getByRole('img', { name: /decisions/ }).waitFor();
  await mg.getByRole('cell', { name: 'officer1' }).waitFor();
  await mg.getByRole('button', { name: 'Open' }).first().click();   // sort header
  await mg.waitForTimeout(800);
  await shot(mg, '17-manager-officers');
});
await step('table view twin of a chart', async () => {
  await mg.getByRole('button', { name: 'Table view' }).first().click();
  await mg.getByRole('table').first().waitFor();
  await mg.getByRole('button', { name: 'Chart view' }).first().click();
});
await step('liability reports: tiles, table, note, CSV download', async () => {
  await mg.goto(`${BASE}/manager/liability`);
  await mg.getByRole('heading', { name: 'Liability reports' }).waitFor();
  await mg.getByText(/Not an actuarial reserve/i).waitFor();
  await mg.waitForTimeout(800);
  await shot(mg, '18-manager-liability');
  const [download] = await Promise.all([mg.waitForEvent('download'), mg.getByRole('button', { name: 'Export CSV' }).click()]);
  if (!/^liability-report-\d{4}-\d{2}-\d{2}\.csv$/.test(download.suggestedFilename())) throw new Error('bad filename ' + download.suggestedFilename());
});

// ------------------------------------------------------------------ admin
console.log('\n[I] Admin');
const ad = await newPage('admin');
await login(ad, 'admin1');
const uname = `ui.test.${Date.now() % 100000}`;
await step('admin dashboard with users-by-role chart', async () => { await ad.getByRole('heading', { name: 'Administration' }).waitFor(); await ad.getByRole('img', { name: /users in each role/ }).waitFor(); await ad.waitForTimeout(700); await shot(ad, '19-admin-dashboard'); });
await step('create user form validates, then creates', async () => {
  await ad.goto(`${BASE}/admin/users/new`);
  await ad.getByRole('button', { name: 'Create user' }).last().click();
  await ad.getByText('Enter a login name.').waitFor();
  await ad.getByLabel('Login name').fill('Bad Name');
  await ad.getByLabel('Initial password').fill('Sup3r-secret');
  await ad.getByRole('button', { name: 'Create user' }).last().click();
  await ad.getByText(/lower-case letters, digits/).first().waitFor();
  await ad.getByLabel('Login name').fill(uname);
  await ad.getByRole('button', { name: 'Create user' }).last().click();
  await confirmDialog(ad, 'Create user');
  await ad.waitForURL(new RegExp(`/admin/users/${uname.replace(/\./g, '\\.')}$`));
});
await step('User Detail shows the account; Edit User changes the role', async () => {
  await ad.getByRole('heading', { name: uname }).waitFor();
  await ad.getByText('Claims Officer').first().waitFor();
  await ad.getByRole('link', { name: 'Edit user' }).click();
  await ad.waitForURL(/\/edit$/);
  await ad.getByRole('combobox', { name: /New role/ }).click({ force: true });
  await ad.getByRole('option', { name: 'Manager' }).click();
  await shot(ad, '19b-admin-edit-user');
  await ad.getByRole('button', { name: 'Change role' }).click();
  await confirmDialog(ad, 'Change role');
  await ad.getByText(`${uname} is now Manager.`).waitFor();
  await ad.waitForURL(new RegExp(`/admin/users/${uname.replace(/\./g, '\\.')}$`));      // back on User Detail
  await ad.getByText('Manager', { exact: true }).first().waitFor();
});
await step('deactivate then activate (with confirmation)', async () => {
  await ad.getByRole('button', { name: 'Deactivate account' }).click();
  await confirmDialog(ad, 'Deactivate');
  await ad.getByText(`${uname} was deactivated.`).waitFor();
  await ad.getByRole('button', { name: 'Activate account' }).click();
  await confirmDialog(ad, 'Activate');
  await ad.getByText(`${uname} was activated.`).waitFor();
});
await step('own account is protected (no role or deactivate controls)', async () => {
  await ad.goto(`${BASE}/admin/users/admin1`);
  await ad.getByText('You cannot deactivate your own account.').waitFor();
  await ad.goto(`${BASE}/admin/users/admin1/edit`);
  await ad.getByText('You cannot change your own role.').waitFor();
});
await step('user list: search by login name, quick deactivate on another row', async () => {
  await ad.goto(`${BASE}/admin/users`);
  await ad.getByLabel('Find a user by login name').fill(uname);
  await ad.getByRole('button', { name: 'Open' }).click();
  await ad.waitForURL(new RegExp(`/admin/users/`));
  await ad.goto(`${BASE}/admin/users`);
  await ad.getByRole('cell', { name: /admin1/ }).waitFor();
  await shot(ad, '20-admin-users');
});

// ------------------------------------------------------------------ responsive
console.log('\n[J] Mobile (390x844) and tablet (820x1180)');
const mo = await newPage('mobile', { width: 390, height: 844 });
await login(mo, 'claimant1');
await step('mobile: dashboard, drawer opens and closes after navigating', async () => {
  await mo.getByRole('heading', { name: /Hello, claimant1/ }).waitFor();
  await shot(mo, '21-mobile-dashboard');
  await mo.getByRole('button', { name: 'Toggle navigation menu' }).click();
  await mo.getByRole('navigation', { name: 'Main navigation' }).getByText('My claims', { exact: true }).waitFor();
  await mo.waitForTimeout(700);                                           // let the drawer finish sliding in
  await shot(mo, '22-mobile-drawer');
  await mo.getByRole('navigation', { name: 'Main navigation' }).getByText('My claims', { exact: true }).click();
  await mo.waitForURL('**/claimant/claims');
  await mo.getByRole('navigation', { name: 'Main navigation' }).waitFor({ state: 'hidden' });
});
await step('mobile: my claims table fits without horizontal page scroll', async () => {
  await mo.getByRole('link', { name: claimId }).waitFor();
  const overflow = await mo.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (overflow > 1) throw new Error(`page scrolls horizontally by ${overflow}px`);
  await shot(mo, '23-mobile-my-claims');
});
await step('mobile: create-claim form is one column and usable', async () => { await mo.goto(`${BASE}/claimant/new-claim`); await mo.getByLabel('Full name').waitFor(); const o = await mo.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth); if (o > 1) throw new Error(`overflow ${o}px`); await shot(mo, '24-mobile-create-claim'); });
const mom = await newPage('mobile-manager', { width: 390, height: 844 });
await login(mom, 'manager1');
await step('mobile: manager dashboard charts fit', async () => { await mom.getByRole('heading', { name: 'Overview' }).waitFor(); await mom.waitForTimeout(900); const o = await mom.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth); if (o > 1) throw new Error(`overflow ${o}px`); await mom.evaluate(() => document.querySelector('.content')?.scrollTo(0, 700)); await shot(mom, '25-mobile-manager'); });
const tab = await newPage('tablet', { width: 820, height: 1180 });
await login(tab, 'officer1');
await step('tablet: officer review workspace', async () => { await tab.goto(`${BASE}/officer/claims/${claimId}`); await tab.getByRole('heading', { name: claimId }).waitFor(); await shot(tab, '26-tablet-review'); });

// ------------------------------------------------------------------ summary
await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n==== UI steps: ${results.length - failed.length} passed, ${failed.length} failed ====`);
failed.forEach((f) => console.log(` - ${f.name}\n     ${f.err}`));

// A 4xx is expected where a step deliberately provokes one (wrong password, non-officer assign, role redirect probes).
const expected = [
  /^\[officer-401\]/,                                                        // the whole page exists to force one 401 (Request Information)
  /^\[officer\] HTTP (400|403|404|409|503) POST \/svc\/assessment\/api\/v1\/officer\/claims\/CLM-[\d-]+\/information-requests/, // forced by request interception
  /^\[officer\] console\.error: Failed to load resource: .*(400|403|404|409|503)/,                                            // Chrome logging those
  /HTTP 401 POST \/svc\/user\/api\/v1\/auth\/login/,                        // the deliberate wrong-password step
  /^\[anon\] console\.error: Failed to load resource: .*401/,               // Chrome logging that same 401
  /HTTP 400 POST \/svc\/claim\/api\/v1\/officer\/claims\/CLM-[\d-]+\/assign/, // the deliberate non-officer assignment
  /^\[manager\] console\.error: Failed to load resource: .*400/,            // Chrome logging that same 400
];
const unexpected = problems.filter((p) => !expected.some((re) => re.test(p)));
console.log(`\n==== Browser problems: ${unexpected.length} unexpected (${problems.length - unexpected.length} expected) ====`);
[...new Set(unexpected)].forEach((p) => console.log(' - ' + p));
process.exit(failed.length || unexpected.length ? 1 : 0);
