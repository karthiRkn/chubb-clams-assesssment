import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ClaimSummary } from '../../../models';
import { ClaimService } from '../../../services/claim.service';
import { NotificationService } from '../../../services/notification.service';

export interface AssignDialogData {
  claim: ClaimSummary;
  /** Officers the manager already knows about (from the officer metrics), with their open workload. Suggestions only. */
  officers: readonly { id: string; open: number }[];
}

/**
 * Assign or reassign a claim: POST /officer/claims/{id}/assign.
 * The backend only accepts an ACTIVE CLAIMS_OFFICER (400 INVALID_OFFICER otherwise). Only ADMIN may list users, so a
 * manager cannot be shown "all officers": the officers found in the workload report are offered as one-click chips (with
 * their open-claim count, which is what a manager wants to see when choosing) and any other login name can be typed and
 * is then validated by the server.
 */
@Component({
  selector: 'app-assign-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.claim.assignedOfficerId ? 'Reassign' : 'Assign' }} {{ data.claim.claimId }}</h2>
    <form (submit)="$event.preventDefault(); submit()" novalidate>
      <mat-dialog-content>
        @if (data.claim.assignedOfficerId) {
          <p class="muted current">Currently assigned to <strong>{{ data.claim.assignedOfficerId }}</strong>.</p>
        }

        @if (data.officers.length > 0) {
          <div class="suggest" role="group" aria-label="Officers on record">
            <span class="muted suggest-title">Officers on record</span>
            <div class="chips">
              @for (option of data.officers; track option.id) {
                <button
                  type="button"
                  class="chip"
                  [class.selected]="officer.value === option.id"
                  [attr.aria-pressed]="officer.value === option.id"
                  (click)="choose(option.id)"
                >
                  {{ option.id }}
                  <span class="count">{{ option.open }} open</span>
                </button>
              }
            </div>
          </div>
        }

        <mat-form-field class="full">
          <mat-label>Claims officer (login name)</mat-label>
          <input
            matInput
            [formControl]="officer"
            autocomplete="off"
            autocapitalize="none"
            spellcheck="false"
            placeholder="e.g. officer1"
          />
          <mat-hint>Must be an active claims officer.</mat-hint>
          @if (officer.hasError('required') || officer.hasError('pattern')) {
            <mat-error>Enter the officer's login name (lower case).</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="saving()">
          {{ saving() ? 'Assigning…' : data.claim.assignedOfficerId ? 'Reassign' : 'Assign' }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .full {
      width: 100%;
    }
    .current {
      margin-bottom: 12px;
    }
    .suggest {
      margin-bottom: 16px;
    }
    .suggest-title {
      display: block;
      margin-bottom: 8px;
      font: var(--mat-sys-label-medium);
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      min-height: 36px;
      padding: 4px 14px;
      border: 1px solid var(--mat-sys-outline);
      border-radius: 999px;
      background: transparent;
      color: var(--mat-sys-on-surface);
      font: var(--mat-sys-label-large);
      cursor: pointer;
    }
    .chip:hover {
      background: var(--mat-sys-surface-container-low);
    }
    .chip.selected {
      border-color: var(--mat-sys-primary);
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
    }
    .count {
      font: var(--mat-sys-body-small);
      color: var(--app-muted);
    }
  `,
})
export class AssignDialog {
  private readonly claims = inject(ClaimService);
  private readonly notifications = inject(NotificationService);
  private readonly dialogRef = inject<MatDialogRef<AssignDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<AssignDialogData>(MAT_DIALOG_DATA);

  protected readonly saving = signal(false);
  protected readonly officer = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9._-]{2,49}$/)],
  });

  protected choose(id: string): void {
    this.officer.setValue(id);
    this.officer.markAsDirty();
  }

  protected submit(): void {
    if (this.officer.invalid) {
      this.officer.markAsTouched();
      return;
    }
    const officerId = this.officer.value.trim();
    this.saving.set(true);
    this.claims.assign(this.data.claim.claimId, officerId).subscribe({
      next: () => {
        this.notifications.success(`${this.data.claim.claimId} is now assigned to ${officerId}.`);
        this.dialogRef.close(true);
      },
      // e.g. 400 INVALID_OFFICER: the error interceptor shows why and the dialog stays open for another name.
      error: () => this.saving.set(false),
    });
  }
}
