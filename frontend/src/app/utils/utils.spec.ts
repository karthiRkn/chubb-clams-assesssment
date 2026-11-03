import { HttpErrorResponse } from '@angular/common/http';
import { FormControl } from '@angular/forms';
import { toCsv } from './csv';
import { fromIsoDate, toIsoDate } from './date';
import { errorCode, errorMessage, errorMessageFor } from './error-message';
import { toHttpParams } from './http-params';
import { maxBytes, maxDecimals, notBlank } from './validators';

const apiError = (status: number, code: string, message: string) =>
  new HttpErrorResponse({ status, error: { timestamp: '', status, error: '', code, message, path: '/x' } });

describe('errorMessage', () => {
  it("uses the backend's message when there is one", () => {
    const err = apiError(409, 'INVALID_CLAIM_TRANSITION', 'A rejected claim cannot be settled.');
    expect(errorMessage(err)).toBe('A rejected claim cannot be settled.');
    expect(errorCode(err)).toBe('INVALID_CLAIM_TRANSITION');
  });

  it('explains an unreachable server', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain('Cannot reach the server');
  });

  it('falls back by status when the body is not the backend error shape', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 503, error: '<html>' }))).toContain('unavailable');
    expect(errorMessage(new HttpErrorResponse({ status: 500, error: 'boom' }))).toContain('unexpected');
    expect(errorMessage(new HttpErrorResponse({ status: 404 }))).toContain('not found');
  });

  it('never throws for odd values', () => {
    expect(errorMessage(undefined)).toBeTruthy();
    expect(errorMessage('text')).toBeTruthy();
    expect(errorCode(new Error('x'))).toBeNull();
  });
});

describe('errorMessageFor', () => {
  const messages = { 403: 'Access denied.', 404: 'Claim not found.', 503: 'Try again later.' };

  it('uses the screen-specific wording for a listed status, even when the backend sent its own', () => {
    expect(errorMessageFor(apiError(403, 'FORBIDDEN', 'raw backend text'), messages)).toBe('Access denied.');
    expect(errorMessageFor(apiError(503, 'DOWNSTREAM_UNAVAILABLE', 'raw'), messages)).toBe('Try again later.');
  });

  it("falls back to the backend's message for a status that is not listed (400 validation, 409 business rule)", () => {
    expect(errorMessageFor(apiError(409, 'INVALID_CLAIM_TRANSITION', 'Claim is not under review.'), messages)).toBe('Claim is not under review.');
    expect(errorMessageFor(apiError(400, 'VALIDATION_FAILED', 'question: must not be blank'), messages)).toBe('question: must not be blank');
  });

  it('still explains an unreachable server and non-HTTP errors', () => {
    expect(errorMessageFor(new HttpErrorResponse({ status: 0 }), messages)).toContain('Cannot reach the server');
    expect(errorMessageFor(new Error('boom'), messages)).toBe('boom');
  });
});

describe('toHttpParams', () => {
  it('drops undefined, null and empty values but keeps false and 0', () => {
    const params = toHttpParams({ status: 'APPROVED', market: null, claimType: undefined, assignedOfficerId: '', assigned: false, page: 0 });
    expect(params.keys().sort()).toEqual(['assigned', 'page', 'status']);
    expect(params.get('assigned')).toBe('false');
    expect(params.get('page')).toBe('0');
  });
});

describe('dates', () => {
  it('formats the local calendar day, not the UTC one', () => {
    expect(toIsoDate(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
    expect(toIsoDate(new Date(2026, 11, 31, 0, 0))).toBe('2026-12-31');
  });

  it('round-trips a LocalDate', () => {
    expect(toIsoDate(fromIsoDate('2026-10-12'))).toBe('2026-10-12');
  });
});

describe('csv', () => {
  it('quotes commas, quotes and line breaks', () => {
    expect(toCsv([['a,b', 'say "hi"', 'x\ny', 3]])).toBe('"a,b","say ""hi""","x\ny",3');
  });

  it('neutralises spreadsheet formulas in text', () => {
    expect(toCsv([['=SUM(A1)', '+1', '-1', '@cmd']])).toBe("'=SUM(A1),'+1,'-1,'@cmd");
  });

  it('leaves a negative NUMBER alone', () => {
    expect(toCsv([[-5, 'ok']])).toBe('-5,ok');
  });
});

describe('validators', () => {
  it('maxDecimals allows 2 decimals and refuses 3', () => {
    const v = maxDecimals(2);
    expect(v(new FormControl(10.5))).toBeNull();
    expect(v(new FormControl(10.25))).toBeNull();
    expect(v(new FormControl(10.001))).toEqual({ maxDecimals: { places: 2 } });
    expect(v(new FormControl(null))).toBeNull();
  });

  it('maxBytes counts bytes, not characters', () => {
    const v = maxBytes(72);
    expect(v(new FormControl('a'.repeat(72)))).toBeNull();
    expect(v(new FormControl('a'.repeat(73)))).not.toBeNull();
    // 40 two-byte characters is 40 characters but 80 bytes.
    expect(v(new FormControl('é'.repeat(40)))).not.toBeNull();
  });

  it('notBlank rejects whitespace-only text but not empty (required handles that)', () => {
    expect(notBlank(new FormControl('   '))).toEqual({ blank: true });
    expect(notBlank(new FormControl(''))).toBeNull();
    expect(notBlank(new FormControl(' a '))).toBeNull();
  });
});
