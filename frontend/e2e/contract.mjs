// Contract test: drives the REAL backend through the Angular dev-server proxy and checks every response against the shapes in src/app/models.
// Needs: the three backend services running and `npm start` (the proxy). Creates claims and users in the backend it runs against.
const BASE = process.env.E2E_BASE ?? 'http://localhost:4200';
const U = `${BASE}/svc/user/api/v1`, C = `${BASE}/svc/claim/api/v1`, A = `${BASE}/svc/assessment/api/v1`;

let passed = 0, failed = 0;
const failures = [];
const ok = (cond, name, detail = '') => {
  if (cond) { passed++; console.log(`  PASS  ${name}`); }
  else { failed++; failures.push(`${name} ${detail}`); console.log(`  FAIL  ${name} ${detail}`); }
};
const keysOf = (o) => Object.keys(o).sort();
const sameKeys = (obj, expected, name) => {
  const got = keysOf(obj), want = [...expected].sort();
  const missing = want.filter((k) => !got.includes(k)), extra = got.filter((k) => !want.includes(k));
  ok(!missing.length && !extra.length, `shape: ${name}`, missing.length || extra.length ? `missing=[${missing}] extra=[${extra}]` : '');
};

async function call(method, url, { token, body } = {}) {
  const res = await fetch(url, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json; try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  return { status: res.status, body: json };
}

const SHAPES = {
  login: ['accessToken', 'tokenType', 'expiresIn', 'username', 'role'],
  user: ['username', 'role', 'active', 'createdAt'],
  claim: ['id', 'claimId', 'claimantId', 'claimantName', 'claimantEmail', 'policyNumber', 'market', 'claimType', 'incidentDescription', 'incidentDate', 'claimedAmount', 'approvedAmount', 'currency', 'status', 'assignedOfficerId', 'decisionReason', 'decidedAt', 'settledAt', 'createdAt', 'updatedAt'],
  summary: ['id', 'claimId', 'claimantId', 'claimantName', 'policyNumber', 'market', 'claimType', 'claimedAmount', 'approvedAmount', 'currency', 'status', 'assignedOfficerId', 'createdAt'],
  page: ['content', 'page', 'size', 'totalElements', 'totalPages'],
  workflow: ['claimId', 'status', 'assignedOfficerId', 'updatedAt'],
  history: ['claimId', 'fromStatus', 'toStatus', 'changedAt', 'actor'],
  assignment: ['claimId', 'previousOfficerId', 'newOfficerId', 'changedAt', 'actor'],
  update: ['claimId', 'changedAt', 'actor', 'changes'],
  infoRequest: ['informationRequestId', 'claimId', 'question', 'status', 'requestedByOfficerId', 'createdAt', 'answer', 'answeredAt'],
  answer: ['informationRequestId', 'claimId', 'status', 'answer', 'answeredAt'],
  assessment: ['claimId', 'officerId', 'kind', 'notes', 'createdAt'],
  decision: ['claimId', 'status', 'decisionReason', 'approvedAmount', 'currency', 'decidedByOfficerId', 'decidedAt', 'updatedAt'],
  settlement: ['claimId', 'status', 'approvedAmount', 'currency', 'settledByOfficerId', 'settledAt', 'updatedAt'],
  mgmtSummary: ['generatedAt', 'liabilityNote', 'outstandingClaimCount', 'outstandingLiabilityByCurrency', 'countsByStatus'],
  currencyLiability: ['currency', 'claimCount', 'estimatedLiability'],
  officerMetrics: ['generatedAt', 'note', 'officers'],
  officerEntry: ['officerId', 'openClaimCount', 'openClaimsByStatus', 'openClaimedAmountByCurrency', 'decidedCount', 'approvedCount', 'settledCount', 'rejectedCount', 'approvedAmountByCurrency'],
  currencyTotal: ['currency', 'claimCount', 'amount'],
};

const claimBody = (over = {}) => ({
  claimantName: 'Mei Tan', claimantEmail: 'mei.tan@example.com', policyNumber: 'POL-2026-0042', market: 'SG', claimType: 'MOTOR',
  incidentDescription: 'Rear bumper damaged in a low-speed collision.', incidentDate: '2026-10-12', claimedAmount: 2400.5, currency: 'SGD', ...over,
});

async function login(username, password = 'password123') {
  const r = await call('POST', `${U}/auth/login`, { body: { username, password } });
  return r;
}

// ---- wait for the dev server + proxy
for (let i = 0; i < 60; i++) {
  try { const r = await fetch(`${BASE}/svc/user/actuator/health`); if (r.ok) break; } catch { /* not up yet */ }
  await new Promise((r) => setTimeout(r, 1500));
}

console.log('\n[1] Authentication (User Service via /svc/user)');
const tokens = {};
for (const [name, role] of [['claimant1', 'CLAIMANT'], ['officer1', 'CLAIMS_OFFICER'], ['manager1', 'MANAGER'], ['admin1', 'ADMIN']]) {
  const r = await login(name);
  ok(r.status === 200, `login ${name} -> 200`, `got ${r.status}`);
  if (r.status === 200) { sameKeys(r.body, SHAPES.login, 'LoginResponse'); ok(r.body.role === role && r.body.tokenType === 'Bearer' && r.body.expiresIn > 0, `${name} role=${role}, Bearer, expiresIn>0`); tokens[name] = r.body.accessToken; }
}
const bad = await login('claimant1', 'wrong-password');
ok(bad.status === 401 && bad.body?.code === 'INVALID_CREDENTIALS', 'wrong password -> 401 INVALID_CREDENTIALS', JSON.stringify(bad.body));
sameKeys(bad.body, ['timestamp', 'status', 'error', 'code', 'message', 'path'], 'ApiError');
const noToken = await call('GET', `${U}/users/me`);
ok(noToken.status === 401 && noToken.body?.code === 'UNAUTHENTICATED', 'no token -> 401 UNAUTHENTICATED');
const me = await call('GET', `${U}/users/me`, { token: tokens.claimant1 });
ok(me.status === 200, 'GET /users/me');
sameKeys(me.body, SHAPES.user, 'User (me)');
const reg = await call('POST', `${U}/auth/register`, { body: { username: `reg${Date.now() % 100000}`, password: 'correct-horse-1' } });
ok(reg.status === 201 && reg.body?.role === 'CLAIMANT', 'register -> 201 CLAIMANT', JSON.stringify(reg.body));

console.log('\n[2] Claimant: create, list, read, edit (Claim Service via /svc/claim)');
const created = await call('POST', `${C}/claims`, { token: tokens.claimant1, body: claimBody() });
ok(created.status === 201, 'POST /claims -> 201', JSON.stringify(created.body));
sameKeys(created.body, SHAPES.claim, 'Claim (create)');
const id = created.body.claimId;
ok(/^CLM-\d{4}-\d{6}$/.test(id) && created.body.status === 'SUBMITTED' && created.body.claimantId === 'claimant1', 'claimId format, SUBMITTED, claimantId from token');
const invalid = await call('POST', `${C}/claims`, { token: tokens.claimant1, body: claimBody({ claimedAmount: -5 }) });
ok(invalid.status === 400 && invalid.body?.code === 'VALIDATION_FAILED', 'negative amount -> 400 VALIDATION_FAILED');
const list = await call('GET', `${C}/claims?size=5`, { token: tokens.claimant1 });
sameKeys(list.body, SHAPES.page, 'Page<ClaimSummary>');
sameKeys(list.body.content[0], SHAPES.summary, 'ClaimSummary');
const filtered = await call('GET', `${C}/claims?status=SUBMITTED&claimType=MOTOR&market=SG&size=1`, { token: tokens.claimant1 });
ok(filtered.status === 200 && filtered.body.content.every((c) => c.status === 'SUBMITTED'), 'list filters (status, claimType, market)');
const edit = await call('PATCH', `${C}/claims/${id}`, { token: tokens.claimant1, body: { claimedAmount: 2500, incidentDescription: 'Updated description.' } });
ok(edit.status === 200 && edit.body.claimedAmount === 2500, 'PATCH while SUBMITTED', JSON.stringify(edit.body));
const empty = await call('PATCH', `${C}/claims/${id}`, { token: tokens.claimant1, body: {} });
ok(empty.status === 400 && empty.body?.code === 'NO_FIELDS_TO_UPDATE', 'empty PATCH -> 400 NO_FIELDS_TO_UPDATE');
const hist = await call('GET', `${C}/claims/${id}/history`, { token: tokens.claimant1 });
sameKeys(hist.body[0], SHAPES.history, 'ClaimHistoryEntry');
ok(hist.body[0].fromStatus === null && hist.body[0].toStatus === 'SUBMITTED' && hist.body[0].actor === 'claimant1', 'creation history entry (from=null, actor=claimant1)');
const upd = await call('GET', `${C}/claims/${id}/updates`, { token: tokens.claimant1 });
sameKeys(upd.body[0], SHAPES.update, 'ClaimUpdateEntry');
ok(Array.isArray(upd.body[0].changes) && upd.body[0].changes.length >= 1, 'update history lists changed fields');
const irEmpty = await call('GET', `${A}/claims/${id}/information-requests`, { token: tokens.claimant1 });
ok(irEmpty.status === 200 && Array.isArray(irEmpty.body) && irEmpty.body.length === 0, 'information-requests empty (Assessment Service via /svc/assessment)');

console.log('\n[3] Scoping and role denials');
const otherClaimant = await call('POST', `${U}/auth/register`, { body: { username: `other${Date.now() % 100000}`, password: 'correct-horse-1' } });
const otherLogin = await login(otherClaimant.body.username, 'correct-horse-1');
const peek = await call('GET', `${C}/claims/${id}`, { token: otherLogin.body.accessToken });
ok(peek.status === 404 && peek.body?.code === 'CLAIM_NOT_FOUND', "another claimant's claim -> 404 CLAIM_NOT_FOUND (same as unknown)");
const claimantMgmt = await call('GET', `${C}/management/summary`, { token: tokens.claimant1 });
ok(claimantMgmt.status === 403 && claimantMgmt.body?.code === 'FORBIDDEN', 'claimant -> management summary 403 FORBIDDEN');
const officerMgmt = await call('GET', `${C}/management/officers`, { token: tokens.officer1 });
ok(officerMgmt.status === 403, 'officer -> management officers 403');
const mgrAssess = await call('GET', `${A}/officer/claims/${id}/assessments`, { token: tokens.manager1 });
ok(mgrAssess.status === 403, 'manager -> assessment history 403 (officer-only)');
const claimantUsers = await call('GET', `${U}/users`, { token: tokens.claimant1 });
ok(claimantUsers.status === 403, 'claimant -> GET /users 403');
const officerOther = await call('POST', `${C}/officer/claims/${id}/assign`, { token: tokens.officer1, body: { officerId: 'someone.else' } });
ok(officerOther.status === 403 && officerOther.body?.code === 'ASSIGNMENT_NOT_PERMITTED', 'officer assigning someone else -> 403 ASSIGNMENT_NOT_PERMITTED', JSON.stringify(officerOther.body));

console.log('\n[4] Manager: assign');
const queueBefore = await call('GET', `${C}/officer/claims?assigned=false&size=100`, { token: tokens.manager1 });
ok(queueBefore.status === 200 && queueBefore.body.content.some((c) => c.claimId === id), 'unassigned list contains the new claim');
const notOfficer = await call('POST', `${C}/officer/claims/${id}/assign`, { token: tokens.manager1, body: { officerId: 'claimant1' } });
ok(notOfficer.status === 400 && notOfficer.body?.code === 'INVALID_OFFICER', 'assign to a non-officer -> 400 INVALID_OFFICER');
const assigned = await call('POST', `${C}/officer/claims/${id}/assign`, { token: tokens.manager1, body: { officerId: 'officer1' } });
ok(assigned.status === 200, 'manager assigns to officer1', JSON.stringify(assigned.body));
sameKeys(assigned.body, SHAPES.workflow, 'WorkflowResponse (assign)');
const assignedList = await call('GET', `${C}/officer/claims?assigned=true&size=100`, { token: tokens.manager1 });
ok(assignedList.body.content.some((c) => c.claimId === id && c.assignedOfficerId === 'officer1'), 'assigned=true list shows officer1');
const asg = await call('GET', `${C}/claims/${id}/assignments`, { token: tokens.manager1 });
sameKeys(asg.body[0], SHAPES.assignment, 'ClaimAssignmentEntry');
ok(asg.body[0].newOfficerId === 'officer1' && asg.body[0].actor === 'manager1', 'assignment history (new=officer1, actor=manager1)');

console.log('\n[5] Officer: review, ask, claimant answers, approve, settle (Assessment Service)');
const review = await call('POST', `${A}/officer/claims/${id}/review`, { token: tokens.officer1, body: { assessmentNotes: 'Photos received; looks consistent.' } });
ok(review.status === 200 && review.body.status === 'UNDER_REVIEW', 'review -> UNDER_REVIEW', JSON.stringify(review.body));
sameKeys(review.body, SHAPES.workflow, 'WorkflowResponse (review)');
const noPatch = await call('PATCH', `${C}/claims/${id}`, { token: tokens.claimant1, body: { claimedAmount: 1 } });
ok(noPatch.status === 409 && noPatch.body?.code === 'CLAIM_NOT_EDITABLE', 'PATCH after review -> 409 CLAIM_NOT_EDITABLE');
const ask = await call('POST', `${A}/officer/claims/${id}/information-requests`, { token: tokens.officer1, body: { question: 'Please send the repair estimate.' } });
ok(ask.status === 201, 'request information -> 201', JSON.stringify(ask.body));
sameKeys(ask.body, SHAPES.infoRequest, 'InformationRequest');
ok(ask.body.status === 'OPEN' && /^IR-\d{4}-\d{6}$/.test(ask.body.informationRequestId) && ask.body.requestedByOfficerId === 'officer1', 'IR id format, OPEN, requestedBy=officer1 (from token)');
const mid = await call('GET', `${C}/claims/${id}`, { token: tokens.claimant1 });
ok(mid.body.status === 'MORE_INFO_REQUIRED', 'claim is MORE_INFO_REQUIRED');
const reqs = await call('GET', `${A}/claims/${id}/information-requests`, { token: tokens.claimant1 });
ok(reqs.body.length === 1 && reqs.body[0].status === 'OPEN', 'claimant sees the OPEN request');
const answered = await call('POST', `${A}/claims/${id}/information`, { token: tokens.claimant1, body: { informationRequestId: ask.body.informationRequestId, answer: 'Estimate attached: 1,800 SGD.' } });
ok(answered.status === 200, 'claimant answers', JSON.stringify(answered.body));
sameKeys(answered.body, SHAPES.answer, 'AnswerResponse');
const resumed = await call('GET', `${C}/claims/${id}`, { token: tokens.claimant1 });
ok(resumed.body.status === 'UNDER_REVIEW', 'claim resumed to UNDER_REVIEW automatically');
const twice = await call('POST', `${A}/claims/${id}/information`, { token: tokens.claimant1, body: { informationRequestId: ask.body.informationRequestId, answer: 'again' } });
ok(twice.status === 409, 'second answer -> 409', `${twice.status} ${twice.body?.code}`);
const tooMuch = await call('POST', `${A}/officer/claims/${id}/decision`, { token: tokens.officer1, body: { decision: 'APPROVED', decisionReason: 'ok', approvedAmount: 99999, currency: 'SGD' } });
ok(tooMuch.status === 400 && tooMuch.body?.code === 'INVALID_APPROVED_AMOUNT', 'approve above claimed -> 400 INVALID_APPROVED_AMOUNT');
const wrongCcy = await call('POST', `${A}/officer/claims/${id}/decision`, { token: tokens.officer1, body: { decision: 'APPROVED', decisionReason: 'ok', approvedAmount: 100, currency: 'USD' } });
ok(wrongCcy.status === 400 && wrongCcy.body?.code === 'CURRENCY_MISMATCH', 'approve in another currency -> 400 CURRENCY_MISMATCH');
const approve = await call('POST', `${A}/officer/claims/${id}/decision`, { token: tokens.officer1, body: { decision: 'APPROVED', decisionReason: 'Covered by the policy.', approvedAmount: 1800, currency: 'SGD' } });
ok(approve.status === 200 && approve.body.status === 'APPROVED', 'approve -> APPROVED', JSON.stringify(approve.body));
sameKeys(approve.body, SHAPES.decision, 'DecisionResponse');
const settle = await call('POST', `${A}/officer/claims/${id}/settlement`, { token: tokens.officer1, body: { settlementNote: 'Paid by bank transfer.' } });
ok(settle.status === 200 && settle.body.status === 'SETTLED', 'settle -> SETTLED', JSON.stringify(settle.body));
sameKeys(settle.body, SHAPES.settlement, 'SettlementResponse');
const finalClaim = await call('GET', `${C}/claims/${id}`, { token: tokens.claimant1 });
ok(finalClaim.body.status === 'SETTLED' && finalClaim.body.approvedAmount === 1800 && finalClaim.body.settledAt, 'claim: SETTLED, approvedAmount 1800, settledAt set');
const trail = await call('GET', `${A}/officer/claims/${id}/assessments`, { token: tokens.officer1 });
sameKeys(trail.body[0], SHAPES.assessment, 'AssessmentEntry');
ok(trail.body.map((e) => e.kind).join(',') === 'NOTE,APPROVED,SETTLED', 'assessment trail kinds NOTE,APPROVED,SETTLED', trail.body.map((e) => e.kind).join(','));

console.log('\n[6] Rejection path, and a rejected claim can never be settled');
const c2 = await call('POST', `${C}/claims`, { token: tokens.claimant1, body: claimBody({ claimType: 'PROPERTY', market: 'HK', currency: 'HKD', claimedAmount: 9000 }) });
const id2 = c2.body.claimId;
const take = await call('POST', `${C}/officer/claims/${id2}/assign`, { token: tokens.officer1, body: { officerId: 'officer1' } });
ok(take.status === 200, 'officer takes a claim for themselves');
await call('POST', `${A}/officer/claims/${id2}/review`, { token: tokens.officer1, body: {} });
const reject = await call('POST', `${A}/officer/claims/${id2}/decision`, { token: tokens.officer1, body: { decision: 'REJECTED', decisionReason: 'Not covered.', currency: 'HKD' } });
ok(reject.status === 200 && reject.body.status === 'REJECTED' && reject.body.approvedAmount === null, 'reject (no amount) -> REJECTED', JSON.stringify(reject.body));
const settleRejected = await call('POST', `${A}/officer/claims/${id2}/settlement`, { token: tokens.officer1, body: {} });
ok(settleRejected.status === 409 && settleRejected.body?.code === 'INVALID_CLAIM_TRANSITION', 'settle a rejected claim -> 409 INVALID_CLAIM_TRANSITION', JSON.stringify(settleRejected.body));
const c3 = await call('POST', `${C}/claims`, { token: tokens.claimant1, body: claimBody({ claimedAmount: 500, market: 'MY', currency: 'MYR' }) });
const queueView = await call('GET', `${C}/officer/claims?assigned=false&size=100`, { token: tokens.officer1 });
ok(queueView.body.content.some((x) => x.claimId === c3.body.claimId), 'officer sees unassigned claim in queue');

console.log('\n[7] Manager reports');
const sum = await call('GET', `${C}/management/summary`, { token: tokens.manager1 });
ok(sum.status === 200, 'GET /management/summary');
sameKeys(sum.body, SHAPES.mgmtSummary, 'ManagementSummary');
if (sum.body.outstandingLiabilityByCurrency.length) sameKeys(sum.body.outstandingLiabilityByCurrency[0], SHAPES.currencyLiability, 'CurrencyLiability');
ok(typeof sum.body.liabilityNote === 'string' && sum.body.liabilityNote.length > 0 && typeof sum.body.countsByStatus === 'object', 'liabilityNote present, countsByStatus is a map');
console.log('        countsByStatus =', JSON.stringify(sum.body.countsByStatus), ' liability =', JSON.stringify(sum.body.outstandingLiabilityByCurrency));
const off = await call('GET', `${C}/management/officers`, { token: tokens.manager1 });
sameKeys(off.body, SHAPES.officerMetrics, 'OfficerMetrics');
const o1 = off.body.officers.find((o) => o.officerId === 'officer1');
ok(!!o1, 'officer1 appears in officer metrics');
if (o1) {
  sameKeys(o1, SHAPES.officerEntry, 'OfficerEntry');
  ok(o1.decidedCount === o1.approvedCount + o1.rejectedCount && o1.settledCount <= o1.approvedCount && o1.rejectedCount >= 1 && o1.settledCount >= 1, 'officer1 counts are consistent: decided = approved + rejected, settled <= approved', JSON.stringify(o1));
  if (o1.approvedAmountByCurrency.length) sameKeys(o1.approvedAmountByCurrency[0], SHAPES.currencyTotal, 'CurrencyTotal');
}

console.log('\n[8] Admin: users');
const uname = `qa.${Date.now() % 100000}`;
const mk = await call('POST', `${U}/users`, { token: tokens.admin1, body: { username: uname, password: 'Sup3r-secret', role: 'CLAIMS_OFFICER' } });
ok(mk.status === 201, 'create user -> 201', JSON.stringify(mk.body));
sameKeys(mk.body, SHAPES.user, 'User (create)');
const dup = await call('POST', `${U}/users`, { token: tokens.admin1, body: { username: uname, password: 'Sup3r-secret', role: 'MANAGER' } });
ok(dup.status === 409 && dup.body?.code === 'USERNAME_TAKEN', 'duplicate -> 409 USERNAME_TAKEN');
const users = await call('GET', `${U}/users?page=0&size=100`, { token: tokens.admin1 });
sameKeys(users.body, SHAPES.page, 'Page<User>');
ok(users.body.content.some((u) => u.username === uname), 'new user appears in the list');
const role = await call('PUT', `${U}/users/${uname}/role`, { token: tokens.admin1, body: { role: 'MANAGER' } });
ok(role.status === 200 && role.body.role === 'MANAGER', 'change role -> MANAGER');
const self = await call('PUT', `${U}/users/admin1/role`, { token: tokens.admin1, body: { role: 'MANAGER' } });
ok(self.status === 409 && self.body?.code === 'CANNOT_CHANGE_OWN_ROLE', 'own role -> 409 CANNOT_CHANGE_OWN_ROLE');
const selfOff = await call('PUT', `${U}/users/admin1/active`, { token: tokens.admin1, body: { active: false } });
ok(selfOff.status === 409 && selfOff.body?.code === 'CANNOT_DEACTIVATE_SELF', 'deactivate self -> 409 CANNOT_DEACTIVATE_SELF');
const off2 = await call('PUT', `${U}/users/${uname}/active`, { token: tokens.admin1, body: { active: false } });
ok(off2.status === 200 && off2.body.active === false, 'deactivate -> active=false');
const blocked = await login(uname, 'Sup3r-secret');
ok(blocked.status === 401 && blocked.body?.code === 'INVALID_CREDENTIALS', 'deactivated user cannot sign in (same 401 as a wrong password)');
const on = await call('PUT', `${U}/users/${uname}/active`, { token: tokens.admin1, body: { active: true } });
ok(on.status === 200 && on.body.active === true, 'activate -> active=true');
const again = await login(uname, 'Sup3r-secret');
ok(again.status === 200 && again.body.role === 'MANAGER', 'reactivated user signs in with the new role');
const nf = await call('GET', `${U}/users/does.not.exist`, { token: tokens.admin1 });
ok(nf.status === 404 && nf.body?.code === 'USER_NOT_FOUND', 'unknown user -> 404 USER_NOT_FOUND');

console.log(`\n==== ${passed} passed, ${failed} failed ====`);
if (failed) { console.log('FAILURES:\n - ' + failures.join('\n - ')); process.exit(1); }
