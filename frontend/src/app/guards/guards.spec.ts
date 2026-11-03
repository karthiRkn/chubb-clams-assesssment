import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, CanActivateFn, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { Role } from '../models';
import { AuthService, SESSION_STORAGE_KEY } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';
import { authGuard, guestGuard, homeRedirectGuard } from './auth.guard';
import { roleGuard } from './role.guard';

function signIn(role: Role, expiresAt = Date.now() + 60_000): void {
  sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ accessToken: 't', username: 'u', role, expiresAt }));
}

function run(guard: CanActivateFn, roles?: Role[], url = '/target'): boolean | string {
  const route = { data: roles ? { roles } : {} } as unknown as ActivatedRouteSnapshot;
  const state = { url } as RouterStateSnapshot;
  const result = TestBed.runInInjectionContext(() => guard(route, state));
  return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : (result as boolean);
}

describe('route guards', () => {
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  describe('authGuard', () => {
    it('lets a signed-in person through', () => {
      signIn('CLAIMANT');
      expect(run(authGuard)).toBe(true);
    });

    it('sends a signed-out visitor to login and remembers where they were going', () => {
      expect(run(authGuard, undefined, '/officer/queue')).toBe('/login?returnUrl=%2Fofficer%2Fqueue');
    });

    it('treats a token that has expired mid-session as signed out', () => {
      vi.useFakeTimers();
      try {
        signIn('CLAIMANT', Date.now() + 1000);
        TestBed.inject(AuthService); // restores the session while it is still valid
        expect(run(authGuard)).toBe(true);

        vi.setSystemTime(Date.now() + 5000);

        expect(run(authGuard, undefined, '/claimant/dashboard')).toBe('/login?returnUrl=%2Fclaimant%2Fdashboard');
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('roleGuard', () => {
    it('allows a role that is listed', () => {
      signIn('MANAGER');
      expect(run(roleGuard, ['MANAGER'])).toBe(true);
    });

    it('allows any of several listed roles', () => {
      signIn('CLAIMS_OFFICER');
      expect(run(roleGuard, ['MANAGER', 'CLAIMS_OFFICER'])).toBe(true);
    });

    it.each<[Role, Role[], string]>([
      ['CLAIMANT', ['CLAIMS_OFFICER'], '/claimant/dashboard'],
      ['CLAIMS_OFFICER', ['MANAGER'], '/officer/dashboard'],
      ['MANAGER', ['ADMIN'], '/manager/dashboard'],
      ['ADMIN', ['CLAIMANT'], '/admin/dashboard'],
    ])('redirects %s away from an area for %j to their own home', (role, allowed, home) => {
      signIn(role);
      const warn = vi.spyOn(TestBed.inject(NotificationService), 'warning');

      expect(run(roleGuard, allowed)).toBe(home);
      expect(warn).toHaveBeenCalledOnce();
    });

    it('sends a signed-out visitor to login, not to a role home', () => {
      expect(run(roleGuard, ['ADMIN'], '/admin/users')).toBe('/login?returnUrl=%2Fadmin%2Fusers');
    });

    it('allows any signed-in role when a route names no roles', () => {
      signIn('CLAIMANT');
      expect(run(roleGuard)).toBe(true);
    });
  });

  describe('guestGuard', () => {
    it('lets a signed-out visitor see the login page', () => {
      expect(run(guestGuard)).toBe(true);
    });

    it('sends a signed-in person to their home instead', () => {
      signIn('ADMIN');
      expect(run(guestGuard)).toBe('/admin/dashboard');
    });
  });

  describe('homeRedirectGuard', () => {
    it('redirects "/" to the home of the role', () => {
      signIn('CLAIMS_OFFICER');
      expect(run(homeRedirectGuard)).toBe('/officer/dashboard');
    });
  });
});
