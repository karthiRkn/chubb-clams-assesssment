import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';
import { EMPTY, switchMap } from 'rxjs';
import { MAX_NOTE_LENGTH } from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { AuthService } from '../../../services/auth.service';
import { ConfirmService } from '../../../services/confirm.service';
import { NotificationService } from '../../../services/notification.service';
import { ClaimBrief } from '../../../shared/claim-brief/claim-brief';
import { createClaimLoader } from '../../../shared/claim-loader';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { actionBlockReason } from '../officer-rules';
import { formatMoney, MoneyPipe } from '../../../shared/money.pipe';

/** Settle an approved claim: POST /officer/claims/{id}/settlement. APPROVED to SETTLED, final. */
@Component({
  selector: 'app-settlement',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MoneyPipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    RouterLink,
    ClaimBrief,
    EmptyState,
    LoadingSpinner,
    PageHeader,
  ],
  template: `
    <div class="page narrow">
      <app-page-header
        title="Settle claim"
        subtitle="Record that the approved amount has been paid."
        [backLink]="back()"
        backLabel="Back to claim"
      />

      @if (loader.error(); as err) {
        <app-empty-state icon="search_off" title="Claim not found" [message]="err.message">
          <a mat-flat-button routerLink="/officer/assigned">Assigned claims</a>
        </app-empty-state>
      } @else if (loader.claim(); as claim) {
        @if (blocked(); as why) {
          <app-empty-state icon="block" title="Not available right now" [message]="why">
            <a mat-flat-button [routerLink]="back()">Back to claim</a>
          </app-empty-state>
        } @else {
          <app-claim-brief [claim]="claim" />

          <form class="panel" (submit)="$event.preventDefault(); submit()" novalidate>
            <div class="amount">
              <span class="muted">Approved amount to settle</span>
              <strong>{{ claim.approvedAmount | money: claim.currency }}</strong>
            </div>
            <mat-form-field class="full">
              <mat-label>Settlement note (optional)</mat-label>
              <textarea matInput rows="4" [formControl]="note" [maxlength]="maxNote" placeholder="For example: paid by bank transfer, reference 12345."></textarea>
              <mat-hint align="end">{{ note.value.length }} / {{ maxNote }}</mat-hint>
            </mat-form-field>
            <div class="actions-end">
              <a mat-button [routerLink]="back()">Cancel</a>
              <button mat-flat-button type="submit" [disabled]="submitting()">
                <mat-icon>paid</mat-icon>{{ submitting() ? 'Settling…' : 'Settle claim' }}
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
      max-width: 820px;
    }
    .full {
      width: 100%;
    }
    .amount {
      display: flex;
      flex-direction: column;
      margin-bottom: 16px;
    }
    .amount strong {
      font: var(--mat-sys-headline-small);
    }
  `,
})
export class Settlement {
  private readonly auth = inject(AuthService);
  private readonly assessment = inject(AssessmentService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  readonly claimId = input.required<string>();
  protected readonly loader = createClaimLoader(this.claimId);

  protected readonly maxNote = MAX_NOTE_LENGTH;
  protected readonly back = computed(() => ['/officer/claims', this.claimId()]);
  protected readonly submitting = signal(false);
  protected readonly note = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(MAX_NOTE_LENGTH)] });

  protected readonly blocked = computed(() => {
    const claim = this.loader.claim();
    return claim ? actionBlockReason(claim, this.auth.username(), 'APPROVED', 'settle') : null;
  });

  protected submit(): void {
    const claim = this.loader.claim();
    if (!claim || this.note.invalid) {
      return;
    }
    const note = this.note.value.trim();
    this.confirm
      .confirm({
        title: 'Settle this claim?',
        message: `Record the settlement of ${formatMoney(claim.approvedAmount, claim.currency)} for ${claim.claimId}. Settlement is final.`,
        confirmLabel: 'Settle claim',
        icon: 'paid',
      })
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.submitting.set(true);
          return this.assessment.settle(claim.claimId, note ? { settlementNote: note } : {});
        }),
      )
      .subscribe({
        next: () => {
          this.notifications.success(`${claim.claimId} was settled.`);
          void this.router.navigate(this.back());
        },
        error: () => this.submitting.set(false),
      });
  }
}
