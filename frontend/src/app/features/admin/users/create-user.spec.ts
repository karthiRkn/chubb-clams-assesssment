import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormGroup } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { CreateUser } from './create-user';

describe('CreateUser (role policy)', () => {
  let fixture: ComponentFixture<CreateUser>;
  let http: HttpTestingController;

  const root = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CreateUser);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('offers the staff roles only, never Claimant', () => {
    (root().querySelector('.mat-mdc-select-trigger') as HTMLElement).click();
    fixture.detectChanges();

    const options = [...document.querySelectorAll('mat-option')].map((o) =>
      o.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(options).toEqual(['Claims Officer', 'Manager', 'Administrator']);
  });

  it('says that claimants register themselves', () => {
    const text = root().textContent?.replace(/\s+/g, ' ') ?? '';
    expect(text).toContain('Claimants register themselves');
  });

  it('refuses a Claimant role even if the control is forced', () => {
    const form = (fixture.componentInstance as unknown as { form: FormGroup }).form;

    form.patchValue({ username: 'new.person', password: 'Sup3r-secret', role: 'CLAIMANT' });

    expect(form.get('role')?.hasError('notProvisionable')).toBe(true);
    expect(form.valid).toBe(false);
  });

  it('accepts each staff role', () => {
    const form = (fixture.componentInstance as unknown as { form: FormGroup }).form;

    for (const role of ['CLAIMS_OFFICER', 'MANAGER', 'ADMIN']) {
      form.patchValue({ username: 'new.person', password: 'Sup3r-secret', role });
      expect(form.valid).toBe(true);
    }
  });
});
