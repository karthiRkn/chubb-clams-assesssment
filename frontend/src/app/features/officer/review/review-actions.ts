import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { EMPTY, switchMap } from 'rxjs';
import { Claim, DecisionType } from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { formatMoney, MoneyPipe } from '../../../shared/money.pipe';
import { maxDecimals, notBlank } from '../../../utils/validators';
import { RequestInformationDialog, RequestInformationResult } from './request-information-dialog';

/**
 * What an officer can do with a claim that is UNDER_REVIEW and assigned to them, side by side:
 *
 *   Approve  - POST /officer/claims/{id}/decision (APPROVED): an amount above zero and not above the claimed amount, in the claim's
 *              own currency, plus a reason.
 *   Reject   - the same endpoint (REJECTED): a reason only (a rejection must not carry an amount). Final: never settled.
 *   Request Information - opens the "Request Additional Information" dialog (POST /officer/claims/{id}/information-requests);
 *              the claim moves to MORE_INFO_REQUIRED until the claimant answers.
 *
 * The workspace shows this panel only for a CLAIMS_OFFICER and only in that status; the backend repeats every rule.
 */
@Component({
  selector: 'app-review-actions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MoneyPipe,
  ],
  template: `
    <section class="panel" aria-label="Review actions">
      <h2>Review actions</h2>
      <p class="muted lead">
        Approve with an amount, reject with a reason, or ask the claimant for more information. A decision is recorded against you
        and cannot be undone.
      </p>

      <div class="choices">
        <mat-button-toggle-group
          aria-label="Decision"
          hideSingleSelectionIndicator
          [value]="mode()"
          (change)="setMode($event.value)"
        >
          <mat-button-toggle value="APPROVED"><mat-icon>check</mat-icon>Approve</mat-button-toggle>
          <mat-button-toggle value="REJECTED"><mat-icon>close</mat-icon>Reject</mat-button-toggle>
        </mat-button-toggle-group>

        <span class="or muted" aria-hidden="true">or</span>

        <button mat-stroked-button type="button" (click)="requestInformation()">
          <mat-icon>help</mat-icon>Request Information
        </button>
      </div>

      <form [formGroup]="form" (submit)="$event.preventDefault(); submit()" novalidate>
        @if (mode() === 'APPROVED') {
          <mat-form-field class="full">
            <mat-label>Approved amount ({{ claim().currency }})</mat-label>
            <input matInput type="number" inputmode="decimal" step="0.01" min="0.01" formControlName="approvedAmount" />
            <mat-hint>Claimed: {{ claim().claimedAmount | money: claim().currency }}. Cannot be more than this.</mat-hint>
            @if (form.controls.approvedAmount.hasError('required')) {
              <mat-error>Enter the amount to approve.</mat-error>
            } @else if (form.controls.approvedAmount.hasError('min')) {
              <mat-error>The amount must be greater than zero.</mat-error>
            } @else if (form.controls.approvedAmount.hasError('overClaimed')) {
              <mat-error>The amount cannot exceed the claimed amount.</mat-error>
            } @else if (form.controls.approvedAmount.hasError('maxDecimals')) {
              <mat-error>Use at most 2 decimal places.</mat-error>
            }
          </mat-form-field>
        }

        <mat-form-field class="full">
          <mat-label>{{ mode() === 'APPROVED' ? 'Reason for approval' : 'Reason for rejection' }}</mat-label>
          <textarea matInput rows="4" formControlName="decisionReason" maxlength="2000"></textarea>
          <mat-hint align="end">{{ form.controls.decisionReason.value.length }} / 2000</mat-hint>
          @if (form.controls.decisionReason.invalid) {
            <mat-error>Give a reason for the decision.</mat-error>
          }
        </mat-form-field>

        @if (mode() === 'REJECTED') {
          <p class="warning">
            <mat-icon aria-hidden="true">warning</mat-icon>
            Rejection is final. A rejected claim cannot be reopened or settled.
          </p>
        }

        <div class="actions-end">
          <button mat-flat-button type="submit" [class.btn-warn]="mode() === 'REJECTED'" [disabled]="submitting()">
            <mat-icon>{{ mode() === 'APPROVED' ? 'check' : 'close' }}</mat-icon>
            @if (submitting()) {
              Saving…
            } @else {
              {{ mode() === 'APPROVED' ? 'Approve claim' : 'Reject claim' }}
            }
          </button>
        </div>
      </form>
    </section>
  `,
  styles: `
    .lead {
      margin-bottom: 16px;
    }
    .choices {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
      margin-bottom: 20px;
    }
    .or {
      font: var(--mat-sys-label-medium);
    }
    .full {
      width: 100%;
    }
    .warning {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 16px;
      color: var(--status-rejected-fg);
    }
  `,
})
export class ReviewActions {
  private readonly fb = inject(FormBuilder);
  private readonly assessment = inject(AssessmentService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly dialog = inject(MatDialog);

  readonly claim = input.required<Claim>();
  /** Emits after something changed on the backend (a decision, a new request, or "the claim is no longer available"), so the workspace reloads. */
  readonly changed = output<void>();

  protected readonly mode = signal<DecisionType>('APPROVED');
  protected readonly submitting = signal(false);

  /**
   * The ceiling for the approved amount. A plain signal, filled in once the `claim` input is bound: a required input cannot be
   * read while the form below is being constructed (that throws NG0950), and the validator runs during construction.
   */
  private readonly maxAmount = signal<number | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    approvedAmount: [0, [Validators.required, Validators.min(0.01), maxDecimals(2), (c: AbstractControl) => this.notOverClaimed(c)]],
    decisionReason: ['', [Validators.required, notBlank, Validators.maxLength(2000)]],
  });

  /** A computed, so a reload that returns the same claimed amount does not wipe what the officer has typed. */
  private readonly claimedAmount = computed(() => this.claim().claimedAmount);

  constructor() {
    // Offer the full claimed amount as the starting point for each claim.
    effect(() => {
      const claimed = this.claimedAmount();
      untracked(() => {
        this.maxAmount.set(claimed);
        this.form.controls.approvedAmount.setValue(claimed);
        this.form.controls.approvedAmount.updateValueAndValidity();
      });
    });
  }

  private notOverClaimed(control: AbstractControl): ValidationErrors | null {
    const max = this.maxAmount();
    return max !== null && Number(control.value) > max ? { overClaimed: true } : null;
  }

  protected setMode(mode: DecisionType): void {
    this.mode.set(mode);
    // A rejection carries no amount, so the amount must not make the form invalid while rejecting.
    if (mode === 'REJECTED') {
      this.form.controls.approvedAmount.disable();
    } else {
      this.form.controls.approvedAmount.enable();
    }
  }

  protected requestInformation(): void {
    this.dialog
      .open<RequestInformationDialog, Claim, RequestInformationResult>(RequestInformationDialog, {
        data: this.claim(),
        width: '600px',
        maxWidth: '96vw',
      })
      .afterClosed()
      .subscribe((result) => {
        if (result?.kind === 'sent') {
          this.notifications.success('Information request sent successfully');
          this.changed.emit();
        } else if (result?.kind === 'stale') {
          // The backend refused because the claim changed or is not ours any more: show the claim as it really is.
          this.changed.emit();
        }
      });
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const claim = this.claim();
    const approving = this.mode() === 'APPROVED';
    const { approvedAmount, decisionReason } = this.form.getRawValue();
    const amount = Number(approvedAmount);

    this.confirm
      .confirm(
        approving
          ? {
              title: 'Approve this claim?',
              message: `Approve ${formatMoney(amount, claim.currency)} for ${claim.claimId}. It will then wait to be settled.`,
              confirmLabel: 'Approve claim',
              icon: 'check_circle',
            }
          : {
              title: 'Reject this claim?',
              message: `${claim.claimId} will be rejected and can never be settled. This cannot be undone.`,
              confirmLabel: 'Reject claim',
              tone: 'warn',
              icon: 'warning',
            },
      )
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.submitting.set(true);
          return this.assessment.decide(claim.claimId, {
            decision: this.mode(),
            decisionReason: decisionReason.trim(),
            // A rejection must not carry an amount; the currency still has to match the claim's.
            ...(approving ? { approvedAmount: amount } : {}),
            currency: claim.currency,
          });
        }),
      )
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.notifications.success(`${claim.claimId} was ${approving ? 'approved' : 'rejected'}.`);
          this.changed.emit();
        },
        error: () => this.submitting.set(false),
      });
  }
}
