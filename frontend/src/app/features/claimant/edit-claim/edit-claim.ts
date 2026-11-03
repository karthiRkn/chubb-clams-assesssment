import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { Router, RouterLink } from '@angular/router';
import {
  Claim,
  CLAIM_STATUS_LABELS,
  ClaimType,
  POLICY_NUMBER_HINT,
  POLICY_NUMBER_PATTERN,
  UpdateClaimRequest,
} from '../../../models';
import { ClaimService } from '../../../services/claim.service';
import { NotificationService } from '../../../services/notification.service';
import { createClaimLoader } from '../../../shared/claim-loader';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { fromIsoDate, toIsoDate } from '../../../utils/date';
import { maxDecimals, notBlank } from '../../../utils/validators';

/**
 * Claim Edit page: PATCH /claims/{claimId}. Only available while the claim is SUBMITTED (an officer starting a review closes it:
 * the backend answers 409 CLAIM_NOT_EDITABLE). Only the fields that changed are sent, because the backend rejects an empty edit.
 * The market and the currency cannot be changed after submission.
 */
@Component({
  selector: 'app-edit-claim',
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
    EmptyState,
    LoadingSpinner,
    PageHeader,
  ],
  template: `
    <div class="page narrow">
      <app-page-header
        [title]="'Edit claim ' + claimId()"
        subtitle="You can correct these details until an officer starts reviewing the claim."
        [backLink]="back()"
        backLabel="Back to claim"
      />

      @if (loader.error(); as err) {
        <app-empty-state icon="search_off" title="Claim not found" [message]="err.message">
          <a mat-flat-button routerLink="/claimant/claims">My claims</a>
        </app-empty-state>
      } @else if (loader.claim(); as claim) {
        @if (claim.status !== 'SUBMITTED') {
          <app-empty-state
            icon="lock"
            title="This claim can no longer be edited"
            [message]="
              'Claims can only be edited while they are Submitted. This one is ' + statusLabel(claim) + '.'
            "
          >
            <a mat-flat-button [routerLink]="back()">Back to claim</a>
          </app-empty-state>
        } @else {
          <form class="panel" [formGroup]="form" (submit)="$event.preventDefault(); save(claim)" novalidate>
            <div class="form-grid">
              <mat-form-field>
                <mat-label>Full name</mat-label>
                <input matInput formControlName="claimantName" maxlength="200" />
                @if (form.controls.claimantName.invalid) {
                  <mat-error>Enter your name.</mat-error>
                }
              </mat-form-field>
              <mat-form-field>
                <mat-label>Email</mat-label>
                <input matInput type="email" formControlName="claimantEmail" maxlength="254" />
                @if (form.controls.claimantEmail.invalid) {
                  <mat-error>Enter a valid email address.</mat-error>
                }
              </mat-form-field>
              <mat-form-field subscriptSizing="dynamic">
                <mat-label>Policy number</mat-label>
                <input matInput formControlName="policyNumber" maxlength="50" />
                <mat-hint>{{ policyHint }}</mat-hint>
                @if (form.controls.policyNumber.invalid) {
                  <mat-error>{{ policyHint }}</mat-error>
                }
              </mat-form-field>
              <mat-form-field>
                <mat-label>Date of incident</mat-label>
                <input matInput [matDatepicker]="picker" [max]="today" formControlName="incidentDate" />
                <mat-datepicker-toggle matIconSuffix [for]="picker" />
                <mat-datepicker #picker />
                @if (form.controls.incidentDate.invalid) {
                  <mat-error>Choose a valid date that is not in the future.</mat-error>
                }
              </mat-form-field>
              <mat-form-field>
                <mat-label>Amount claimed ({{ claim.currency }})</mat-label>
                <input matInput type="number" inputmode="decimal" step="0.01" min="0.01" formControlName="claimedAmount" />
                @if (form.controls.claimedAmount.invalid) {
                  <mat-error>Enter an amount above zero, with at most 2 decimals.</mat-error>
                }
              </mat-form-field>
              <div class="type">
                <label id="edit-type" class="muted">Claim type</label>
                <mat-radio-group formControlName="claimType" aria-labelledby="edit-type" class="radios">
                  <mat-radio-button value="MOTOR">Motor</mat-radio-button>
                  <mat-radio-button value="PROPERTY">Property</mat-radio-button>
                </mat-radio-group>
              </div>
              <mat-form-field class="full">
                <mat-label>What happened?</mat-label>
                <textarea matInput rows="5" formControlName="incidentDescription" maxlength="2000"></textarea>
                <mat-hint align="end">{{ form.controls.incidentDescription.value.length }} / 2000</mat-hint>
                @if (form.controls.incidentDescription.invalid) {
                  <mat-error>Describe what happened.</mat-error>
                }
              </mat-form-field>
            </div>

            <p class="muted fixed">The market ({{ claim.market }}) and currency ({{ claim.currency }}) cannot be changed after submission.</p>

            <div class="actions-end">
              <a mat-button [routerLink]="back()">Cancel</a>
              <button mat-flat-button type="submit" [disabled]="saving() || !hasChanges(claim)">
                <mat-icon>save</mat-icon>{{ saving() ? 'Saving…' : 'Save changes' }}
              </button>
            </div>
          </form>
        }
      } @else {
        <app-loading-spinner label="Loading claim…" />
      }
    </div>
  `,
  styles: `
    .narrow {
      max-width: 860px;
    }
    .type {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-bottom: 16px;
    }
    .radios {
      display: flex;
      gap: 16px;
    }
    .fixed {
      margin-bottom: 16px;
    }
  `,
})
export class EditClaim {
  private readonly fb = inject(FormBuilder);
  private readonly claims = inject(ClaimService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  /** The `:claimId` route parameter. */
  readonly claimId = input.required<string>();
  protected readonly loader = createClaimLoader(this.claimId);

  protected readonly back = computed(() => ['/claimant/claims', this.claimId()]);
  protected readonly policyHint = POLICY_NUMBER_HINT;
  protected readonly today = new Date();
  protected readonly saving = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    claimantName: ['', [Validators.required, notBlank, Validators.maxLength(200)]],
    claimantEmail: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    policyNumber: ['', [Validators.required, Validators.pattern(POLICY_NUMBER_PATTERN)]],
    claimType: ['MOTOR' as ClaimType, [Validators.required]],
    incidentDate: new FormControl<Date | null>(null, Validators.required),
    claimedAmount: new FormControl<number | null>(null, [Validators.required, Validators.min(0.01), maxDecimals(2)]),
    incidentDescription: ['', [Validators.required, notBlank, Validators.maxLength(2000)]],
  });

  constructor() {
    // Fill the form once the claim has loaded (and again if a different claim is opened).
    effect(() => {
      const claim = this.loader.claim();
      if (claim) {
        untracked(() => {
          this.form.reset({
            claimantName: claim.claimantName,
            claimantEmail: claim.claimantEmail,
            policyNumber: claim.policyNumber,
            claimType: claim.claimType,
            incidentDate: fromIsoDate(claim.incidentDate),
            claimedAmount: claim.claimedAmount,
            incidentDescription: claim.incidentDescription,
          });
        });
      }
    });
  }

  protected statusLabel(claim: Claim): string {
    return CLAIM_STATUS_LABELS[claim.status];
  }

  /** The fields that differ from the stored claim. */
  private changes(claim: Claim): UpdateClaimRequest {
    const v = this.form.getRawValue();
    const changes: UpdateClaimRequest = {};
    if (v.claimantName.trim() !== claim.claimantName) changes.claimantName = v.claimantName.trim();
    if (v.claimantEmail.trim() !== claim.claimantEmail) changes.claimantEmail = v.claimantEmail.trim();
    if (v.policyNumber.trim() !== claim.policyNumber) changes.policyNumber = v.policyNumber.trim();
    if (v.claimType !== claim.claimType) changes.claimType = v.claimType;
    if (v.incidentDate && toIsoDate(v.incidentDate) !== claim.incidentDate) changes.incidentDate = toIsoDate(v.incidentDate);
    if (v.claimedAmount !== null && Number(v.claimedAmount) !== claim.claimedAmount) changes.claimedAmount = Number(v.claimedAmount);
    if (v.incidentDescription.trim() !== claim.incidentDescription) changes.incidentDescription = v.incidentDescription.trim();
    return changes;
  }

  protected hasChanges(claim: Claim): boolean {
    return Object.keys(this.changes(claim)).length > 0;
  }

  protected save(claim: Claim): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const changes = this.changes(claim);
    if (Object.keys(changes).length === 0) {
      return;
    }
    this.saving.set(true);
    this.claims.update(claim.claimId, changes).subscribe({
      next: () => {
        this.notifications.success('Your changes were saved.');
        void this.router.navigate(this.back());
      },
      // The error interceptor shows why (for example 409 CLAIM_NOT_EDITABLE if an officer started reviewing meanwhile).
      error: () => this.saving.set(false),
    });
  }
}
