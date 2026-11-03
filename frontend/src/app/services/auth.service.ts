import { HttpClient, HttpContext } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { map, Observable, tap } from 'rxjs';
import { API } from '../config/api.config';
import { SKIP_ERROR_TOAST } from '../interceptors/http-context';
import { AuthSession, LoginRequest, LoginResponse, RegisterRequest, Role, User } from '../models';
import { NotificationService } from './notification.service';

/** sessionStorage, not localStorage: the token must not outlive the browser tab. */
export const SESSION_STORAGE_KEY = 'chubb.claims.session';

/** Where each role lands after login and when it is turned away from a page it may not see. */
export const HOME_ROUTES: Record<Role, string> = {
  CLAIMANT: '/claimant/dashboard',
  CLAIMS_OFFICER: '/officer/dashboard',
  MANAGER: '/manager/dashboard',
  ADMIN: '/admin/dashboard',
};

/** setTimeout cannot hold more than 2^31-1 ms. */
const MAX_TIMER_MS = 2_147_483_647;

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);

  private expiryTimer: ReturnType<typeof setTimeout> | undefined;

  private readonly _session = signal<AuthSession | null>(null);
  readonly session = this._session.asReadonly();
  readonly username = computed(() => this._session()?.username ?? null);
  readonly role = computed(() => this._session()?.role ?? null);
  readonly isLoggedIn = computed(() => this._session() !== null);

  constructor() {
    this.restore();
    // A pending expiry timer must not outlive the service (tests, hot reload).
    inject(DestroyRef).onDestroy(() => clearTimeout(this.expiryTimer));
  }

  /** POST /auth/login. A wrong password surfaces as a 401 INVALID_CREDENTIALS for the form to show inline. */
  login(request: LoginRequest): Observable<AuthSession> {
    return this.http
      .post<LoginResponse>(`${API.user}/auth/login`, request, {
        context: new HttpContext().set(SKIP_ERROR_TOAST, true),
      })
      .pipe(
        map((response) => this.toSession(response)),
        tap((session) => this.start(session)),
      );
  }

  /** POST /auth/register - creates a CLAIMANT account; the caller still has to log in. */
  register(request: RegisterRequest): Observable<User> {
    return this.http.post<User>(`${API.user}/auth/register`, request, {
      context: new HttpContext().set(SKIP_ERROR_TOAST, true),
    });
  }

  /**
   * Ends the session and goes to the login page.
   * `expired` is for a token the backend (or the clock) has stopped accepting; it keeps the current URL so the person
   * lands back where they were after signing in again.
   */
  logout(options: { expired?: boolean } = {}): void {
    const returnUrl = options.expired ? this.router.url : undefined;
    this.clear();
    if (options.expired) {
      this.notifications.warning('Your session has expired. Please sign in again.');
    }
    const onLogin = this.router.url.startsWith('/login');
    void this.router.navigate(['/login'], {
      queryParams: returnUrl && !onLogin && returnUrl !== '/' ? { returnUrl } : undefined,
    });
  }

  /** The bearer token, or null if there is none or its lifetime is over. */
  getToken(): string | null {
    const session = this._session();
    return session && session.expiresAt > Date.now() ? session.accessToken : null;
  }

  hasValidSession(): boolean {
    return this.getToken() !== null;
  }

  hasRole(...roles: Role[]): boolean {
    const role = this.role();
    return role !== null && roles.includes(role);
  }

  homeRoute(role: Role | null = this.role()): string {
    return role ? HOME_ROUTES[role] : '/login';
  }

  private toSession(response: LoginResponse): AuthSession {
    return {
      accessToken: response.accessToken,
      username: response.username,
      role: response.role,
      expiresAt: Date.now() + response.expiresIn * 1000,
    };
  }

  private start(session: AuthSession): void {
    this._session.set(session);
    this.persist(session);
    this.scheduleExpiry(session);
  }

  private clear(): void {
    clearTimeout(this.expiryTimer);
    this.expiryTimer = undefined;
    this._session.set(null);
    try {
      sessionStorage.removeItem(SESSION_STORAGE_KEY);
    } catch {
      /* storage unavailable (private mode, blocked): nothing to remove */
    }
  }

  private persist(session: AuthSession): void {
    try {
      sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
    } catch {
      /* the session still works in memory; it just will not survive a page reload */
    }
  }

  private restore(): void {
    try {
      const raw = sessionStorage.getItem(SESSION_STORAGE_KEY);
      if (!raw) {
        return;
      }
      const stored = JSON.parse(raw) as Partial<AuthSession>;
      const valid =
        typeof stored.accessToken === 'string' &&
        typeof stored.username === 'string' &&
        typeof stored.role === 'string' &&
        typeof stored.expiresAt === 'number' &&
        stored.expiresAt > Date.now();
      if (valid) {
        const session = stored as AuthSession;
        this._session.set(session);
        this.scheduleExpiry(session);
      } else {
        sessionStorage.removeItem(SESSION_STORAGE_KEY);
      }
    } catch {
      /* unreadable or unavailable storage: start logged out */
    }
  }

  /** Signs the person out at the moment the token stops being valid, instead of on their next failed request. */
  private scheduleExpiry(session: AuthSession): void {
    clearTimeout(this.expiryTimer);
    const remaining = Math.min(Math.max(session.expiresAt - Date.now(), 0), MAX_TIMER_MS);
    this.expiryTimer = setTimeout(() => {
      if (this._session()?.accessToken === session.accessToken) {
        this.logout({ expired: true });
      }
    }, remaining);
  }
}
