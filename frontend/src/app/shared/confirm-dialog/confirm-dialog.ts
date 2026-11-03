import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** `warn` styles the confirm button as a destructive action. */
  tone?: 'primary' | 'warn';
  icon?: string;
}

@Component({
  selector: 'app-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="title">
      @if (data.icon) {
        <mat-icon [class.warn]="data.tone === 'warn'">{{ data.icon }}</mat-icon>
      }
      {{ data.title }}
    </h2>
    <mat-dialog-content>
      <p class="message">{{ data.message }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="false">{{ data.cancelLabel ?? 'Cancel' }}</button>
      <button
        mat-flat-button
        type="button"
        cdkFocusInitial
        [class.btn-warn]="data.tone === 'warn'"
        [mat-dialog-close]="true"
      >
        {{ data.confirmLabel ?? 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .title {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .message {
      margin: 0;
      white-space: pre-line;
    }
    mat-icon.warn {
      color: var(--mat-sys-error);
    }
  `,
})
export class ConfirmDialog {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
}
