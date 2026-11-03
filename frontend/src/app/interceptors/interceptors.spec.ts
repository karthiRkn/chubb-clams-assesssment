import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService, SESSION_STORAGE_KEY } from '../services/auth.service';
import { LoadingService } from '../services/loading.service';
import { NotificationService } from '../services/notification.service';
import { errorInterceptor } from './error.interceptor';
import { SKIP_ERROR_TOAST } from './http-context';
import { jwtInterceptor } from './jwt.interceptor';
import { loadingInterceptor } from './loading.interceptor';
import { HttpContext } from '@angular/common/http';

function setup(signedIn = true) {
  sessionStorage.clear();
  if (signedIn) {
    sessionStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ accessToken: 'the-jwt', username: 'officer1', role: 'CLAIMS_OFFICER', expiresAt: Date.now() + 60_000 }),
    );
  }
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([loadingInterceptor, jwtInterceptor, errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([{ path: 'login', children: [] }]),
    ],
  });
  return {
    http: TestBed.inject(HttpClient),
    ctrl: TestBed.inject(HttpTestingController),
    auth: TestBed.inject(AuthService),
    notifications: TestBed.inject(NotificationService),
    loading: TestBed.inject(LoadingService),
  };
}

describe('jwtInterceptor', () => {
  it.each(['/svc/user/api/v1/users/me', '/svc/claim/api/v1/claims', '/svc/assessment/api/v1/claims/CLM-1/information-requests'])(
    'adds the bearer token to %s',
    (url) => {
      const { http, ctrl } = setup();
      http.get(url).subscribe();
      expect(ctrl.expectOne(url).request.headers.get('Authorization')).toBe('Bearer the-jwt');
    },
  );

  it.each(['/svc/user/api/v1/auth/login', '/svc/user/api/v1/auth/register'])('does not send the token to %s', (url) => {
    const { http, ctrl } = setup();
    http.post(url, {}).subscribe();
    expect(ctrl.expectOne(url).request.headers.has('Authorization')).toBe(false);
  });

  it('never sends the token to a URL that is not one of the backend services', () => {
    const { http, ctrl } = setup();
    http.get('https://example.com/data').subscribe();
    expect(ctrl.expectOne('https://example.com/data').request.headers.has('Authorization')).toBe(false);
  });

  it('ends the session on a 401 from a backend call', () => {
    const { http, ctrl, auth } = setup();
    http.get('/svc/claim/api/v1/claims').subscribe({ error: () => undefined });
    ctrl
      .expectOne('/svc/claim/api/v1/claims')
      .flush({ status: 401, code: 'UNAUTHENTICATED', message: 'x' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.isLoggedIn()).toBe(false);
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('keeps the session on a 403 (wrong role is not an expired token)', () => {
    const { http, ctrl, auth } = setup();
    http.get('/svc/claim/api/v1/management/summary').subscribe({ error: () => undefined });
    ctrl
      .expectOne('/svc/claim/api/v1/management/summary')
      .flush({ status: 403, code: 'FORBIDDEN', message: 'nope' }, { status: 403, statusText: 'Forbidden' });

    expect(auth.isLoggedIn()).toBe(true);
  });
});

describe('errorInterceptor', () => {
  it("shows the backend's own message as a toast and still re-throws", () => {
    const { http, ctrl, notifications } = setup();
    const error = vi.spyOn(notifications, 'error');
    let thrown = false;

    http.post('/svc/assessment/api/v1/officer/claims/CLM-1/settlement', {}).subscribe({ error: () => (thrown = true) });
    ctrl
      .expectOne('/svc/assessment/api/v1/officer/claims/CLM-1/settlement')
      .flush(
        { status: 409, code: 'INVALID_CLAIM_TRANSITION', message: 'A rejected claim cannot be settled.' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(error).toHaveBeenCalledWith('A rejected claim cannot be settled.');
    expect(thrown).toBe(true);
  });

  it('explains an unreachable backend (status 0)', () => {
    const { http, ctrl, notifications } = setup();
    const error = vi.spyOn(notifications, 'error');
    http.get('/svc/claim/api/v1/claims').subscribe({ error: () => undefined });
    ctrl.expectOne('/svc/claim/api/v1/claims').error(new ProgressEvent('error'), { status: 0 });
    expect(error).toHaveBeenCalledWith(expect.stringContaining('Cannot reach the server'));
  });

  it('stays quiet when the caller shows the error itself', () => {
    const { http, ctrl, notifications } = setup(false);
    const error = vi.spyOn(notifications, 'error');
    http
      .post('/svc/user/api/v1/auth/login', {}, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) })
      .subscribe({ error: () => undefined });
    ctrl.expectOne('/svc/user/api/v1/auth/login').flush({}, { status: 400, statusText: 'Bad Request' });
    expect(error).not.toHaveBeenCalled();
  });

  it('does not toast a 401 (the session is ended with its own notice)', () => {
    const { http, ctrl, notifications } = setup();
    const error = vi.spyOn(notifications, 'error');
    http.get('/svc/claim/api/v1/claims').subscribe({ error: () => undefined });
    ctrl.expectOne('/svc/claim/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(error).not.toHaveBeenCalled();
  });
});

describe('loadingInterceptor', () => {
  it('is busy while a request is in flight and idle after, even when it fails', () => {
    const { http, ctrl, loading } = setup();
    expect(loading.isLoading()).toBe(false);

    http.get('/svc/claim/api/v1/claims').subscribe({ error: () => undefined });
    expect(loading.isLoading()).toBe(true);

    ctrl.expectOne('/svc/claim/api/v1/claims').flush({}, { status: 500, statusText: 'Server Error' });
    expect(loading.isLoading()).toBe(false);
  });
});
