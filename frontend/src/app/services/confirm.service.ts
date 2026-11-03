import { inject, Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { map, Observable } from 'rxjs';
import { ConfirmDialog, ConfirmDialogData } from '../shared/confirm-dialog/confirm-dialog';

/** Asks "are you sure?" before a state-changing action. Emits `true` only when the person confirms. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(MatDialog);

  confirm(data: ConfirmDialogData): Observable<boolean> {
    return this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data,
        width: '440px',
        maxWidth: '92vw',
        autoFocus: 'dialog',
      })
      .afterClosed()
      .pipe(map((result) => result === true));
  }
}
