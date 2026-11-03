import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthSession } from '../models';
import { AuthService, SESSION_STORAGE_KEY } from './auth.service';

const LOGIN_URL = '/svc/user/api/v1/auth/login';

function create(): { auth: AuthService; http: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: 'login', children: [] }]),
    ],
  });
  return { auth: TestBed.inject(AuthService), http: TestBed.inject(HttpTestingController) };
}

function store(session: Partial<AuthSession>): void {
  sessionStorage.setItem(
    SESSION_STORAGE_KEY,
    JSON.stringify({ accessToken: 'stored', username: 'claimant1', role: 'CLAIMANT', expiresAt: Date.now() + 60_000, ...session }),
  );
}

describe('AuthService', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it('logs in against the User Service and keeps the session in sessionStorage only', () => {
    const { auth, http } = create();

    let session: AuthSession | undefined;
    auth.login({ username: 'officer1', password: 'password123' }).subscribe((s) => (session = s));

    const req = http.expectOne(LOGIN_URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'officer1', password: 'password123' });
    req.flush({ accessToken: 'jwt-1', tokenType: 'Bearer', expiresIn: 3600, username: 'officer1', role: 'CLAIMS_OFFICER' });

    expect(session?.role).toBe('CLAIMS_OFFICER');
    expect(auth.isLoggedIn()).toBe(true);
    expect(auth.username()).toBe('officer1');
    expect(auth.role()).toBe('CLAIMS_OFFICER');
    expect(auth.getToken()).toBe('jwt-1');
    expect(auth.homeRoute()).toBe('/officer/dashboard');
    expect(JSON.parse(sessionStorage.getItem(SESSION_STORAGE_KEY) ?? '{}').accessToken).toBe('jwt-1');
    expect(localStorage.length).toBe(0);
  });

  it('computes the expiry from expiresIn', () => {
    const { auth, http } = create();
    const before = Date.now();
    auth.login({ username: 'a', password: 'b' }).subscribe();
    http.expectOne(LOGIN_URL).flush({ accessToken: 't', tokenType: 'Bearer', expiresIn: 3600, username: 'a', role: 'ADMIN' });

    const expiresAt = auth.session()?.expiresAt ?? 0;
    expect(expiresAt).toBeGreaterThanOrEqual(before + 3_600_000);
    expect(expiresAt).toBeLessThanOrEqual(Date.now() + 3_600_000);
  });

  it('does not log in on a 401 and leaves nothing behind', () => {
    const { auth, http } = create();
    let failed = false;
    auth.login({ username: 'a', password: 'wrong' }).subscribe({ error: () => (failed = true) });
    http.expectOne(LOGIN_URL).flush({ status: 401, code: 'INVALID_CREDENTIALS', message: 'x' }, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(auth.isLoggedIn()).toBe(false);
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('restores a still-valid session after a page reload', () => {
    store({ accessToken: 'abc', role: 'MANAGER', username: 'manager1' });
    const { auth } = create();

    expect(auth.isLoggedIn()).toBe(true);
    expect(auth.getToken()).toBe('abc');
    expect(auth.hasRole('MANAGER')).toBe(true);
    expect(auth.hasRole('ADMIN')).toBe(false);
  });

  it('discards an expired stored session', () => {
    store({ expiresAt: Date.now() - 1000 });
    const { auth } = create();

    expect(auth.isLoggedIn()).toBe(false);
    expect(auth.getToken()).toBeNull();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('discards a corrupt stored session', () => {
    sessionStorage.setItem(SESSION_STORAGE_KEY, '{not json');
    const { auth } = create();
    expect(auth.isLoggedIn()).toBe(false);
  });

  it('logout clears the session and the stored token', () => {
    store({});
    const { auth } = create();
    expect(auth.isLoggedIn()).toBe(true);

    auth.logout();

    expect(auth.isLoggedIn()).toBe(false);
    expect(auth.getToken()).toBeNull();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('does not hand out a token whose lifetime is over', () => {
    vi.useFakeTimers();
    try {
      store({ expiresAt: Date.now() + 1000 });
      const { auth } = create();
      expect(auth.getToken()).toBe('stored');

      vi.setSystemTime(Date.now() + 2000);

      expect(auth.getToken()).toBeNull();
      expect(auth.hasValidSession()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('signs the person out by itself when the token expires', () => {
    vi.useFakeTimers();
    try {
      store({ expiresAt: Date.now() + 1000 });
      const { auth } = create();
      expect(auth.isLoggedIn()).toBe(true);

      vi.advanceTimersByTime(1001);

      expect(auth.isLoggedIn()).toBe(false);
      expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('maps every role to its own home page', () => {
    const { auth } = create();
    expect(auth.homeRoute('CLAIMANT')).toBe('/claimant/dashboard');
    expect(auth.homeRoute('CLAIMS_OFFICER')).toBe('/officer/dashboard');
    expect(auth.homeRoute('MANAGER')).toBe('/manager/dashboard');
    expect(auth.homeRoute('ADMIN')).toBe('/admin/dashboard');
    expect(auth.homeRoute(null)).toBe('/login');
  });

  it('registers through POST /auth/register', () => {
    const { auth, http } = create();
    auth.register({ username: 'mei.tan', password: 'correct-horse' }).subscribe();
    const req = http.expectOne('/svc/user/api/v1/auth/register');
    expect(req.request.method).toBe('POST');
    req.flush({ username: 'mei.tan', role: 'CLAIMANT', active: true, createdAt: new Date().toISOString() });
  });
});
