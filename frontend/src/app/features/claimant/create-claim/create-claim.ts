import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { EMPTY, switchMap } from 'rxjs';
import { CURRENCIES, MARKETS } from '../../../config/api.config';
import { ClaimType, CreateClaimRequest, POLICY_NUMBER_HINT, POLICY_NUMBER_PATTERN } from '../../../models';
import { ClaimService } from '../../../services/claim.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { PageHeader } from '../../../shared/page-header/page-header';
import { toIsoDate } from '../../../utils/date';
import { maxDecimals, notBlank } from '../../../utils/validators';

/** Report an incident: POST /claims. The server generates the claim reference and takes the claimant from the token. */
@Component({
  selector: 'app-create-claim',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatRadioModule,
    MatSelectModule,
    PageHeader,
  ],
  template: `
    <div class="page narrow">
      <app-page-header
        title="Report a claim"
        subtitle="Tell us what happened. You can correct the details until an officer starts reviewing your claim."
        backLink="/claimant/claims"
        backLabel="My claims"
      />

      <form class="panel" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <h2>About you and your policy</h2>
        <div class="form-grid">
          <mat-form-field>
            <mat-label>Full name</mat-label>
            <input matInput formControlName="claimantName" autocomplete="name" maxlength="200" />
            @if (form.controls.claimantName.hasError('required') || form.controls.claimantName.hasError('blank')) {
              <mat-error>Enter your name.</mat-error>
            }
          </mat-form-field>

          <mat-form-field>
            <mat-label>Email</mat-label>
            <input matInput type="email" formControlName="claimantEmail" autocomplete="email" maxlength="254" />
            @if (form.controls.claimantEmail.hasError('required')) {
              <mat-error>Enter your email address.</mat-error>
            } @else if (form.controls.claimantEmail.hasError('email')) {
              <mat-error>Enter a valid email address.</mat-error>
            }
          </mat-form-field>

          <mat-form-field subscriptSizing="dynamic">
            <mat-label>Policy number</mat-label>
            <input matInput formControlName="policyNumber" maxlength="50" autocapitalize="characters" />
            <mat-hint>{{ policyHint }}</mat-hint>
            @if (form.controls.policyNumber.hasError('required')) {
              <mat-error>Enter your policy number.</mat-error>
            } @else if (form.controls.policyNumber.hasError('pattern')) {
              <mat-error>{{ policyHint }}</mat-error>
            }
          </mat-form-field>

          <mat-form-field>
            <mat-label>Market</mat-label>
            <mat-select formControlName="market">
              @for (m of markets; track m.code) {
                <mat-option [value]="m.code">{{ m.code }} · {{ m.name }}</mat-option>
              }
            </mat-select>
            @if (form.controls.market.hasError('required')) {
              <mat-error>Choose the market of your policy.</mat-error>
            }
          </mat-form-field>
        </div>

        <h2 class="section">About the incident</h2>
        <div class="form-grid">
          <div class="full type">
            <label id="type-label" class="muted">Claim type</label>
            <mat-radio-group formControlName="claimType" aria-labelledby="type-label" class="radios">
              <mat-radio-button value="MOTOR">Motor</mat-radio-button>
              <mat-radio-button value="PROPERTY">Property</mat-radio-button>
            </mat-radio-group>
          </div>

          <mat-form-field>
            <mat-label>Date of incident</mat-label>
            <input matInput [matDatepicker]="picker" [max]="today" formControlName="incidentDate" />
            <mat-datepicker-toggle matIconSuffix [for]="picker" />
            <mat-datepicker #picker />
            @if (form.controls.incidentDate.hasError('required')) {
              <mat-error>Choose the date it happened.</mat-error>
            } @else if (form.controls.incidentDate.hasError('matDatepickerMax')) {
              <mat-error>The date cannot be in the future.</mat-error>
            } @else if (form.controls.incidentDate.hasError('matDatepickerParse')) {
              <mat-error>Enter a valid date.</mat-error>
            }
          </mat-form-field>

          <div class="amount-row">
            <mat-form-field class="amount">
              <mat-label>Amount claimed</mat-label>
              <input matInput type="number" inputmode="decimal" step="0.01" min="0.01" formControlName="claimedAmount" />
              @if (form.controls.claimedAmount.hasError('required')) {
                <mat-error>Enter an amount.</mat-error>
              } @else if (form.controls.claimedAmount.hasError('min')) {
                <mat-error>The amount must be greater than zero.</mat-error>
              } @else if (form.controls.claimedAmount.hasError('maxDecimals')) {
                <mat-error>Use at most 2 decimal places.</mat-error>
              }
            </mat-form-field>
            <mat-form-field class="currency">
              <mat-label>Currency</mat-label>
              <mat-select formControlName="currency">
                @for (c of currencies; track c) {
                  <mat-option [value]="c">{{ c }}</mat-option>
                }
              </mat-select>
              @if (form.controls.currency.hasError('required')) {
                <mat-error>Choose a currency.</mat-error>
              }
            </mat-form-field>
          </div>

          <mat-form-field class="full">
            <mat-label>What happened?</mat-label>
            <textarea matInput rows="5" formControlName="incidentDescription" maxlength="2000"></textarea>
            <mat-hint align="end">{{ form.controls.incidentDescription.value.length }} / 2000</mat-hint>
            @if (form.controls.incidentDescription.hasError('required') || form.controls.incidentDescription.hasError('blank')) {
              <mat-error>Describe what happened.</mat-error>
            }
          </mat-form-field>
        </div>

        <div class="actions-end buttons">
          <a mat-button routerLink="/claimant/claims">Cancel</a>
          <button mat-flat-button type="submit" [disabled]="submitting()">
            <mat-icon>send</mat-icon>
            {{ submitting() ? 'Submitting…' : 'Submit claim' }}
          </button>
        </div>
      </form>
    </div>
  `,
  styles: `
    .narrow {
      max-width: 860px;
    }
    .section {
      margin-top: 8px;
    }
    h2 {
      font: var(--mat-sys-title-medium);
      margin-bottom: 12px;
    }
    .type {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-bottom: 16px;
    }
    .radios {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
    }
    .amount-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 150px;
      gap: 12px;
    }
    .buttons {
      margin-top: 8px;
    }
  `,
})
export class CreateClaim {
  private readonly fb = inject(FormBuilder);
  private readonly claims = inject(ClaimService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly markets = MARKETS;
  protected readonly currencies = CURRENCIES;
  protected readonly policyHint = POLICY_NUMBER_HINT;
  protected readonly today = new Date();
  protected readonly submitting = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    claimantName: ['', [Validators.required, notBlank, Validators.maxLength(200)]],
    claimantEmail: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    policyNumber: ['', [Validators.required, Validators.pattern(POLICY_NUMBER_PATTERN)]],
    market: ['', [Validators.required]],
    claimType: ['MOTOR' as ClaimType, [Validators.required]],
    incidentDate: new FormControl<Date | null>(null, Validators.required),
    claimedAmount: new FormControl<number | null>(null, [Validators.required, Validators.min(0.01), maxDecimals(2)]),
    currency: ['', [Validators.required]],
    incidentDescription: ['', [Validators.required, notBlank, Validators.maxLength(2000)]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.notifications.warning('Please fix the highlighted fields.');
      return;
    }

    const value = this.form.getRawValue();
    const request: CreateClaimRequest = {
      claimantName: value.claimantName.trim(),
      claimantEmail: value.claimantEmail.trim(),
      policyNumber: value.policyNumber.trim(),
      market: value.market,
      claimType: value.claimType,
      incidentDescription: value.incidentDescription.trim(),
      incidentDate: toIsoDate(value.incidentDate as Date),
      claimedAmount: Number(value.claimedAmount),
      currency: value.currency,
    };

    this.confirm
      .confirm({
        title: 'Submit this claim?',
        message: `You are claiming ${request.claimedAmount.toFixed(2)} ${request.currency} for an incident on ${request.incidentDate}.`,
        confirmLabel: 'Submit claim',
        icon: 'send',
      })
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.submitting.set(true);
          return this.claims.create(request);
        }),
      )
      .subscribe({
        next: (claim) => {
          this.notifications.success(`Claim ${claim.claimId} was submitted.`);
          void this.router.navigate(['/claimant/claims', claim.claimId]);
        },
        error: () => this.submitting.set(false),
      });
  }
}
