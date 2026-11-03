import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { EMPTY, switchMap } from 'rxjs';
import { InformationRequest, MAX_ANSWER_LENGTH } from '../../models';
import { AssessmentService } from '../../services/assessment.service';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';

/**
 * One information request: the officer's question and, once answered, the claimant's answer.
 * When `canAnswer` is true and the request is still OPEN, shows the answer form (text only; there is no document upload).
 */
@Component({
  selector: 'app-information-request-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <article class="item" [class.open]="request().status === 'OPEN'">
      <header class="head">
        <span class="id mono">{{ request().informationRequestId }}</span>
        <span class="state" [class.open]="request().status === 'OPEN'">
          <mat-icon aria-hidden="true">{{ request().status === 'OPEN' ? 'hourglass_top' : 'check_circle' }}</mat-icon>
          {{ request().status === 'OPEN' ? 'Waiting for answer' : 'Answered' }}
        </span>
      </header>

      <p class="question">{{ request().question }}</p>
      <p class="meta muted">Asked by {{ request().requestedByOfficerId }} · {{ request().createdAt | date: 'medium' }}</p>

      @if (request().status === 'ANSWERED') {
        <div class="answer">
          <div class="muted answer-label">Answer · {{ request().answeredAt | date: 'medium' }}</div>
          <p class="text">{{ request().answer }}</p>
        </div>
      } @else if (canAnswer()) {
        <form class="form" (submit)="$event.preventDefault(); submit()" novalidate>
          <mat-form-field class="field">
            <mat-label>Your answer</mat-label>
            <textarea
              matInput
              rows="4"
              [formControl]="answer"
              [maxlength]="maxLength"
              placeholder="Type the information the officer asked for"
            ></textarea>
            <mat-hint align="end">{{ answer.value.length }} / {{ maxLength }}</mat-hint>
            @if (answer.hasError('required') || answer.hasError('pattern')) {
              <mat-error>Please enter an answer.</mat-error>
            }
          </mat-form-field>
          <div class="actions-end">
            <button mat-flat-button type="submit" [disabled]="submitting()">
              <mat-icon>send</mat-icon>
              {{ submitting() ? 'Sending…' : 'Send answer' }}
            </button>
          </div>
        </form>
      }
    </article>
  `,
  styles: `
    .item {
      padding: 16px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius);
      background: var(--mat-sys-surface-container-lowest);
    }
    .item.open {
      border-color: var(--status-info-fg);
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }
    .state {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font: var(--mat-sys-label-medium);
      color: var(--status-approved-fg);
    }
    .state.open {
      color: var(--status-info-fg);
    }
    .state mat-icon {
      width: 18px;
      height: 18px;
      font-size: 18px;
    }
    .question {
      font: var(--mat-sys-title-small);
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .meta {
      font: var(--mat-sys-body-small);
      margin-top: 4px;
    }
    .answer {
      margin-top: 12px;
      padding: 12px;
      border-radius: 8px;
      background: var(--mat-sys-surface-container-low);
    }
    .answer-label {
      font: var(--mat-sys-label-medium);
      margin-bottom: 4px;
    }
    .text {
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .form {
      margin-top: 12px;
    }
    .field {
      width: 100%;
    }
  `,
})
export class InformationRequestItem {
  private readonly assessment = inject(AssessmentService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  readonly request = input.required<InformationRequest>();
  /** Only the claim's own claimant may answer; officers and managers see the request read-only. */
  readonly canAnswer = input(false);
  /** Emits after the answer was accepted, so the parent can reload the claim (it resumes review by itself). */
  readonly answered = output<void>();

  protected readonly maxLength = MAX_ANSWER_LENGTH;
  protected readonly submitting = signal(false);
  protected readonly answer = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/\S/)],
  });

  protected submit(): void {
    if (this.answer.invalid) {
      this.answer.markAsTouched();
      return;
    }
    const request = this.request();
    this.confirm
      .confirm({
        title: 'Send your answer?',
        message: 'You cannot change an answer once it has been sent.',
        confirmLabel: 'Send answer',
        icon: 'send',
      })
      .pipe(
        switchMap((ok) => {
          if (!ok) {
            return EMPTY;
          }
          this.submitting.set(true);
          return this.assessment.answerInformation(request.claimId, {
            informationRequestId: request.informationRequestId,
            answer: this.answer.value.trim(),
          });
        }),
      )
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.notifications.success('Your answer was sent to the claims officer.');
          this.answered.emit();
        },
        // The error interceptor already showed the backend's message; the form stays editable so the person can retry.
        error: () => this.submitting.set(false),
      });
  }
}
