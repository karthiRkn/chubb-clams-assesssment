import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { Claim, InformationRequest } from '../../../models';
import { SKIP_ERROR_TOAST } from '../../../interceptors/http-context';
import { MAX_QUESTION_LENGTH, REQUEST_INFORMATION_ERRORS, RequestInformationDialog } from './request-information-dialog';

const URL = '/svc/assessment/api/v1/officer/claims/CLM-2026-000101/information-requests';

const claim = { claimId: 'CLM-2026-000101', claimantName: 'Mei Tan', status: 'UNDER_REVIEW' } as Claim;

const created: InformationRequest = {
  informationRequestId: 'IR-2026-000001',
  claimId: claim.claimId,
  question: 'Please provide RC copy and accident photos.',
  status: 'OPEN',
  requestedByOfficerId: 'officer1',
  createdAt: '2026-10-27T08:00:00Z',
  answer: null,
  answeredAt: null,
};

describe('RequestInformationDialog', () => {
  let fixture: ComponentFixture<RequestInformationDialog>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn>; disableClose: boolean; keydownEvents: () => Subject<KeyboardEvent> };
  let escape: Subject<KeyboardEvent>;

  const root = () => fixture.nativeElement as HTMLElement;
  const textarea = () => root().querySelector('textarea') as HTMLTextAreaElement;
  const sendButton = () => root().querySelector('button[type="submit"]') as HTMLButtonElement;
  const cancelButton = () => [...root().querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Cancel') as HTMLButtonElement;
  /** The banner's message only (its icon is a font ligature whose name would otherwise end up in the text). */
  const alertText = () => root().querySelector('[role="alert"] span')?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
  const fieldError = () => root().querySelector('mat-error')?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

  /** Types like a person would: set the value, fire `input`, so the form and the view both notice. */
  function type(text: string): void {
    textarea().value = text;
    textarea().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function send(): void {
    sendButton().click();
    fixture.detectChanges();
  }

  beforeEach(() => {
    escape = new Subject<KeyboardEvent>();
    dialogRef = { close: vi.fn(), disableClose: false, keydownEvents: () => escape };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: claim },
      ],
    });
    fixture = TestBed.createComponent(RequestInformationDialog);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('shows the requested title, field and buttons', () => {
    expect(root().querySelector('h2')?.textContent?.trim()).toBe('Request Additional Information');
    expect(root().textContent).toContain('Question');
    expect(textarea().placeholder).toBe('Please describe what additional information is required.');
    expect(cancelButton()).toBeTruthy();
    expect(sendButton().textContent).toContain('Send Request');
  });

  it('does not close on a stray click outside (a half-written question must survive)', () => {
    expect(dialogRef.disableClose).toBe(true);
  });

  describe('validation', () => {
    it('requires a question and sends nothing', () => {
      send();
      expect(fieldError()).toBe('Question is required.');
      http.expectNone(URL);
    });

    it('treats whitespace as empty', () => {
      type('     ');
      send();
      expect(fieldError()).toBe('Question is required.');
      http.expectNone(URL);
    });

    it('accepts exactly 2000 characters', () => {
      type('a'.repeat(MAX_QUESTION_LENGTH));
      send();
      http.expectOne(URL).flush(created, { status: 201, statusText: 'Created' });
    });

    it('refuses 2001 characters with a message (it does not silently cut the text)', () => {
      type('a'.repeat(MAX_QUESTION_LENGTH + 1));
      expect(textarea().value.length).toBe(MAX_QUESTION_LENGTH + 1);
      send();
      expect(fieldError()).toBe(`Question must be ${MAX_QUESTION_LENGTH} characters or fewer.`);
      http.expectNone(URL);
    });
  });

  describe('sending', () => {
    it('POSTs the trimmed question, keeps errors inline, and closes with the created request', () => {
      type('  Please provide RC copy and accident photos.  ');
      send();

      const req = http.expectOne(URL);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ question: 'Please provide RC copy and accident photos.' });
      expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
      expect(sendButton().disabled).toBe(true); // no double submit while in flight
      expect(sendButton().textContent).toContain('Sending');

      req.flush(created, { status: 201, statusText: 'Created' });
      expect(dialogRef.close).toHaveBeenCalledWith({ kind: 'sent', request: created });
    });

    it('does not send twice when the button is pressed again while in flight', () => {
      type('One question');
      send();
      send();
      http.expectOne(URL).flush(created, { status: 201, statusText: 'Created' });
    });

    it('adds a quick prompt to what is already written', () => {
      type('Please send the invoice.');
      const chip = root().querySelector('.chip') as HTMLButtonElement;
      chip.click();
      fixture.detectChanges();
      expect(textarea().value).toBe(`Please send the invoice. ${chip.textContent?.trim()}`);
    });

    it('sends with Ctrl+Enter', () => {
      type('Quick one');
      textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }));
      http.expectOne(URL).flush(created, { status: 201, statusText: 'Created' });
    });
  });

  describe('errors', () => {
    const fail = (status: number, body: object = {}) => {
      type('Please send the estimate.');
      send();
      http.expectOne(URL).flush(body, { status, statusText: 'x' });
      fixture.detectChanges();
    };
    const api = (status: number, code: string, message: string) => ({ timestamp: '', status, error: '', code, message, path: URL });

    it('400: shows the backend validation message under the field and lets the person edit and retry', () => {
      fail(400, api(400, 'VALIDATION_FAILED', 'question: size must be between 0 and 2000'));
      expect(fieldError()).toBe('question: size must be between 0 and 2000');
      expect(alertText()).toBeNull();
      expect(sendButton().disabled).toBe(false);
      send(); // still invalid until edited: no second request
      http.expectNone(URL);
      type('Please send the estimate, thanks.');
      send();
      http.expectOne(URL).flush(created, { status: 201, statusText: 'Created' });
    });

    it('403: access denied, and sending is no longer offered', () => {
      fail(403, api(403, 'FORBIDDEN', 'raw backend text'));
      expect(alertText()).toBe(REQUEST_INFORMATION_ERRORS[403]);
      expect(sendButton().disabled).toBe(true);
    });

    it('404: claim not found, and sending is no longer offered', () => {
      fail(404, api(404, 'CLAIM_NOT_FOUND', 'raw backend text'));
      expect(alertText()).toBe(REQUEST_INFORMATION_ERRORS[404]);
      expect(sendButton().disabled).toBe(true);
    });

    it("409: shows the backend's business message, and sending is no longer offered", () => {
      fail(409, api(409, 'INVALID_CLAIM_TRANSITION', 'Claim CLM-2026-000101 is not under review.'));
      expect(alertText()).toBe('Claim CLM-2026-000101 is not under review.');
      expect(sendButton().disabled).toBe(true);
    });

    it('503: service unavailable, and the person can try again', () => {
      fail(503, api(503, 'DOWNSTREAM_UNAVAILABLE', 'Claim Service is unavailable'));
      expect(alertText()).toBe(REQUEST_INFORMATION_ERRORS[503]);
      expect(sendButton().disabled).toBe(false);
    });

    it('a network failure says the server cannot be reached', () => {
      type('Please send the estimate.');
      send();
      http.expectOne(URL).error(new ProgressEvent('error'), { status: 0 });
      fixture.detectChanges();
      expect(alertText()).toContain('Cannot reach the server');
    });

    it('401: shows nothing here (the session interceptor sends the person to login)', () => {
      fail(401, api(401, 'UNAUTHENTICATED', 'Missing token'));
      expect(alertText()).toBeNull();
      expect(fieldError()).toBeNull();
    });

    it.each([403, 404, 409])('%i: Cancel tells the workspace to reload (the claim is not what it showed)', (status) => {
      fail(status, api(status, 'X', 'x'));
      cancelButton().click();
      expect(dialogRef.close).toHaveBeenCalledWith({ kind: 'stale' });
    });

    it('Cancel after a plain cancel closes with nothing', () => {
      cancelButton().click();
      expect(dialogRef.close).toHaveBeenCalledWith(undefined);
    });

    it('Escape cancels like the Cancel button', () => {
      escape.next(new KeyboardEvent('keydown', { key: 'Escape' }));
      expect(dialogRef.close).toHaveBeenCalledWith(undefined);
    });

    it('cannot be cancelled while the request is in flight', () => {
      type('Please send the estimate.');
      send();
      cancelButton().click();
      expect(dialogRef.close).not.toHaveBeenCalled();
      http.expectOne(URL).flush(created, { status: 201, statusText: 'Created' });
    });
  });
});
