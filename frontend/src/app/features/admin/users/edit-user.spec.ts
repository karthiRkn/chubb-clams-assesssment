import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { Role, User } from '../../../models';
import { SESSION_STORAGE_KEY } from '../../../services/auth.service';
import { ConfirmService } from '../../../services/confirm.service';
import { EditUser } from './edit-user';

const BASE = '/svc/user/api/v1/users';

const account = (username: string, role: Role): User => ({
  username,
  role,
  active: true,
  createdAt: '2026-10-27T08:00:00Z',
});

/** The members the page keeps to itself, reached only to prove a refused role is never sent. */
interface Internals {
  role: FormControl<Role>;
  changeRole(user: User): void;
}

describe('EditUser (role policy)', () => {
  let fixture: ComponentFixture<EditUser>;
  let http: HttpTestingController;

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const select = () => root().querySelector('mat-select');

  /** Opens the real page for `user` and lets it load. */
  async function open(user: User): Promise<void> {
    fixture = TestBed.createComponent(EditUser);
    fixture.componentRef.setInput('username', user.username);
    fixture.detectChanges();
    http.expectOne(`${BASE}/${user.username}`).flush(user);
    await fixture.whenStable();
    fixture.detectChanges();
    fixture.detectChanges();
  }

  /** Opens the role picker and reads what it offers. */
  function roleOptions(): string[] {
    (root().querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
    fixture.detectChanges();
    return [...document.querySelectorAll('mat-option')].map(
      (option) => option.textContent?.replace(/\s+/g, ' ').trim() ?? '',
    );
  }

  beforeEach(() => {
    sessionStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({
        accessToken: 't',
        username: 'admin1',
        role: 'ADMIN',
        expiresAt: Date.now() + 60_000,
      }),
    );
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('gives a claimant no role control, says why, and points to Create user', async () => {
    await open(account('claimant1', 'CLAIMANT'));

    expect(select()).toBeNull();
    expect(text()).toContain('Claimants register themselves and stay claimants');
    expect(root().querySelector('button')).toBeNull();
    expect(root().querySelector('a[href="/admin/users/new"]')?.textContent).toContain(
      'Create a staff user',
    );
  });

  it.each<[Role, string[]]>([
    ['CLAIMS_OFFICER', ['Claims Officer', 'Manager', 'Administrator']],
    ['MANAGER', ['Claims Officer', 'Manager', 'Administrator']],
  ])('offers a %s the staff roles and never Claimant', async (role, expected) => {
    await open(account('staff.one', role));

    expect(roleOptions()).toEqual(expected);
  });

  it('starts on the current role with Change role disabled until something else is chosen', async () => {
    await open(account('staff.one', 'CLAIMS_OFFICER'));

    const change = [...root().querySelectorAll('button')].find((b) =>
      b.textContent?.includes('Change role'),
    ) as HTMLButtonElement;
    expect(change.disabled).toBe(true);
  });

  it('never sends or even asks about a role the policy refuses, even if the control is forced', async () => {
    await open(account('staff.one', 'MANAGER'));
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(true));
    const page = fixture.componentInstance as unknown as Internals;

    page.role.setValue('CLAIMANT');
    fixture.detectChanges();
    page.changeRole(account('staff.one', 'MANAGER'));

    expect(page.role.hasError('roleTransition')).toBe(true);
    expect(ask).not.toHaveBeenCalled();
    http.expectNone(`${BASE}/staff.one/role`);
  });

  it('sends an allowed move once it is confirmed', async () => {
    await open(account('staff.one', 'CLAIMS_OFFICER'));
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(true));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const page = fixture.componentInstance as unknown as Internals;

    page.role.setValue('MANAGER');
    page.changeRole(account('staff.one', 'CLAIMS_OFFICER'));

    const put = http.expectOne(`${BASE}/staff.one/role`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ role: 'MANAGER' });
    put.flush(account('staff.one', 'MANAGER'));
    expect(navigate).toHaveBeenCalledWith(['/admin/users', 'staff.one']);
  });

  it('keeps the existing protection for the administrator’s own account', async () => {
    await open(account('admin1', 'ADMIN'));

    expect(select()).toBeNull();
    expect(text()).toContain('You cannot change your own role');
  });
});
