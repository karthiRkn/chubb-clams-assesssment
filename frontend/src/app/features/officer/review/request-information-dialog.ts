import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { filter } from 'rxjs';
import { Claim, InformationRequest } from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { errorMessageFor } from '../../../utils/error-message';
import { notBlank } from '../../../utils/validators';

/** The backend rejects a longer question (400), so the form says so before sending. */
export const MAX_QUESTION_LENGTH = 2000;

/**
 * What each failure of POST /officer/claims/{id}/information-requests means for the person looking at the dialog.
 * 400 (validation) and 409 (business rule) are not listed on purpose: the backend's own message is the useful one there.
 * 401 is not listed either: the session interceptor already ends the session and redirects to the login page.
 */
export const REQUEST_INFORMATION_ERRORS: Partial<Record<number, string>> = {
  403: 'Access denied. You do not have permission to request information on this claim.',
  404: 'Claim not found. It may have been removed, or it is assigned to another officer.',
  503: 'The service is temporarily unavailable. Please try again in a few moments.',
};

/** Short, common questions an officer can drop into the box with one click. */
const QUICK_PROMPTS = [
  'Please send photos of the damage.',
  'Please send the repair estimate.',
  'Please provide a copy of the police report.',
  'Please provide proof of ownership.',
] as const;

/**
 * Closes with `sent` when the request was created, or `stale` when the backend said the claim can no longer take a request
 * (403, 404 or 409): the workspace must then reload so it shows the claim as it really is.
 */
export type RequestInformationResult = { kind: 'sent'; request: InformationRequest } | { kind: 'stale' };

/**
 * Request Additional Information: POST /officer/claims/{id}/information-requests with `{ question }`.
 * The claim goes UNDER_REVIEW to MORE_INFO_REQUIRED and returns to UNDER_REVIEW by itself once every open request is answered.
 *
 * Errors are shown inside the dialog (the request opts out of the global toast):
 *   400 under the field · 403 / 404 / 409 / 503 in the banner (403, 404 and 409 also stop further attempts) · 401 redirects to login.
 */
@Component({
  selector: 'app-request-information-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Request Additional Information</h2>
    <form (submit)="$event.preventDefault(); send()" novalidate>
      <mat-dialog-content>
        <p class="muted intro">
          Ask {{ claim.claimantName }} for what you need to decide {{ claim.claimId }}. The claim moves to “More info required”
          until they answer. Answers are text only.
        </p>

        @if (failure(); as message) {
          <div class="alert" role="alert">
            <mat-icon aria-hidden="true">error</mat-icon>
            <span>{{ message }}</span>
          </div>
        }

        <mat-form-field class="full">
          <mat-label>Question</mat-label>
          <textarea
            matInput
            cdkFocusInitial
            rows="6"
            [formControl]="question"
            placeholder="Please describe what additional information is required."
            (keydown.control.enter)="send()"
            (keydown.meta.enter)="send()"
          ></textarea>
          <mat-hint>Ctrl + Enter to send</mat-hint>
          <mat-hint align="end" [class.over]="question.value.length > maxLength">{{ question.value.length }} / {{ maxLength }}</mat-hint>
          @if (question.hasError('required') || question.hasError('blank')) {
            <mat-error>Question is required.</mat-error>
          } @else if (question.hasError('maxlength')) {
            <mat-error>Question must be {{ maxLength }} characters or fewer.</mat-error>
          } @else if (question.hasError('server')) {
            <mat-error>{{ question.getError('server') }}</mat-error>
          }
        </mat-form-field>

        <div class="prompts" role="group" aria-label="Quick prompts">
          <span class="muted prompts-title">Quick prompts</span>
          @for (prompt of prompts; track prompt) {
            <button type="button" class="chip" [disabled]="sending() || blocked()" (click)="append(prompt)">{{ prompt }}</button>
          }
        </div>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button type="button" [disabled]="sending()" (click)="cancel()">Cancel</button>
        <button mat-flat-button type="submit" [disabled]="sending() || blocked()">
          <mat-icon>send</mat-icon>{{ sending() ? 'Sending…' : 'Send Request' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .full {
      width: 100%;
    }
    .intro {
      margin-bottom: 12px;
    }
    .alert {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      margin-bottom: 16px;
      padding: 12px;
      border-radius: 8px;
      background: var(--status-rejected-bg);
      color: var(--status-rejected-fg);
    }
    .over {
      color: var(--mat-sys-error);
      font-weight: 500;
    }
    .prompts {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .prompts-title {
      flex-basis: 100%;
      font: var(--mat-sys-label-medium);
    }
    .chip {
      min-height: 32px;
      padding: 4px 12px;
      border: 1px solid var(--mat-sys-outline);
      border-radius: 999px;
      background: transparent;
      color: var(--mat-sys-on-surface);
      font: var(--mat-sys-label-medium);
      text-align: left;
      cursor: pointer;
    }
    .chip:hover:not(:disabled) {
      background: var(--mat-sys-surface-container-low);
    }
    .chip:disabled {
      opacity: 0.5;
      cursor: default;
    }
  `,
})
export class RequestInformationDialog {
  private readonly assessment = inject(AssessmentService);
  private readonly dialogRef = inject<MatDialogRef<RequestInformationDialog, RequestInformationResult>>(MatDialogRef);
  protected readonly claim = inject<Claim>(MAT_DIALOG_DATA);

  protected readonly maxLength = MAX_QUESTION_LENGTH;
  protected readonly prompts = QUICK_PROMPTS;
  protected readonly sending = signal(false);
  /** A banner message for failures that are not about the question text itself. */
  protected readonly failure = signal<string | null>(null);
  /** The claim can no longer take a request (403/404/409): sending again cannot help, and the workspace needs a reload. */
  protected readonly blocked = signal(false);

  protected readonly question = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, notBlank, Validators.maxLength(MAX_QUESTION_LENGTH)],
  });

  constructor() {
    // A stray click outside the dialog must not throw away a question that is half written; Cancel and Escape still close it.
    this.dialogRef.disableClose = true;
    this.dialogRef
      .keydownEvents()
      .pipe(
        filter((event) => event.key === 'Escape'),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.cancel());
  }

  protected append(prompt: string): void {
    const current = this.question.value.trimEnd();
    this.question.setValue(current ? `${current} ${prompt}` : prompt);
    this.question.markAsDirty();
  }

  protected cancel(): void {
    if (!this.sending()) {
      this.dialogRef.close(this.blocked() ? { kind: 'stale' } : undefined);
    }
  }

  protected send(): void {
    if (this.sending() || this.blocked()) {
      return;
    }
    if (this.question.invalid) {
      this.question.markAsTouched();
      return;
    }
    this.failure.set(null);
    this.sending.set(true);
    this.assessment
      .requestInformation(this.claim.claimId, { question: this.question.value.trim() }, { inlineErrors: true })
      .subscribe({
        next: (request) => this.dialogRef.close({ kind: 'sent', request }),
        error: (err: unknown) => this.fail(err),
      });
  }

  private fail(err: unknown): void {
    this.sending.set(false);
    const status = err instanceof HttpErrorResponse ? err.status : 0;
    if (status === 401) {
      return; // the session interceptor is already sending the person to the login page
    }
    const message = errorMessageFor(err, REQUEST_INFORMATION_ERRORS);
    if (status === 400) {
      this.question.setErrors({ server: message });
      this.question.markAsTouched();
    } else {
      this.failure.set(message);
    }
    if (status === 403 || status === 404 || status === 409) {
      this.blocked.set(true);
    }
  }
}
