import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { InformationRequest } from '../../models';
import { EmptyState } from '../empty-state/empty-state';

/**
 * A claim's information requests as a table (read-only): Request ID, Question, Status, Created, Answer, Answered.
 * Open requests come first, then the newest. Used where nobody answers (officer workspace, manager overview);
 * the claimant answers through `app-information-request-list`.
 */
@Component({
  selector: 'app-information-requests-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatIconModule, MatTableModule, EmptyState],
  template: `
    @if (requests().length === 0) {
      <app-empty-state icon="forum" title="No information requests" [message]="emptyMessage()" />
    } @else {
      <div class="table-wrap">
        <table mat-table [dataSource]="sorted()" aria-label="Information requests">
          <ng-container matColumnDef="id">
            <th mat-header-cell *matHeaderCellDef>Request ID</th>
            <td mat-cell *matCellDef="let r" class="mono nowrap">{{ r.informationRequestId }}</td>
          </ng-container>

          <ng-container matColumnDef="question">
            <th mat-header-cell *matHeaderCellDef>Question</th>
            <td mat-cell *matCellDef="let r" class="text">{{ r.question }}</td>
          </ng-container>

          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let r">
              <span class="state" [class.open]="r.status === 'OPEN'">
                <mat-icon aria-hidden="true">{{ r.status === 'OPEN' ? 'hourglass_top' : 'check_circle' }}</mat-icon>
                {{ r.status === 'OPEN' ? 'Open' : 'Answered' }}
              </span>
            </td>
          </ng-container>

          <ng-container matColumnDef="createdAt">
            <th mat-header-cell *matHeaderCellDef>Created</th>
            <td mat-cell *matCellDef="let r" class="nowrap">{{ r.createdAt | date: 'medium' }}</td>
          </ng-container>

          <ng-container matColumnDef="answer">
            <th mat-header-cell *matHeaderCellDef>Answer</th>
            <td mat-cell *matCellDef="let r" class="text">
              @if (r.answer) {
                {{ r.answer }}
              } @else {
                <span class="muted">Awaiting answer</span>
              }
            </td>
          </ng-container>

          <ng-container matColumnDef="answeredAt">
            <th mat-header-cell *matHeaderCellDef>Answered</th>
            <td mat-cell *matCellDef="let r" class="nowrap">
              @if (r.answeredAt) {
                {{ r.answeredAt | date: 'medium' }}
              } @else {
                <span class="muted">—</span>
              }
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns"></tr>
        </table>
      </div>
    }
  `,
  styles: `
    table {
      min-width: 900px;
    }
    td {
      vertical-align: top;
      padding-top: 12px;
      padding-bottom: 12px;
    }
    .text {
      max-width: 320px;
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .state {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 10px 2px 6px;
      border-radius: 999px;
      font: var(--mat-sys-label-medium);
      white-space: nowrap;
      background: var(--status-approved-bg);
      color: var(--status-approved-fg);
    }
    .state.open {
      background: var(--status-info-bg);
      color: var(--status-info-fg);
    }
    .state mat-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
    }
  `,
})
export class InformationRequestsTable {
  readonly requests = input.required<readonly InformationRequest[]>();
  readonly emptyMessage = input('Requests for more information will appear here.');

  protected readonly columns = ['id', 'question', 'status', 'createdAt', 'answer', 'answeredAt'];

  protected readonly sorted = computed(() =>
    [...this.requests()].sort((a, b) => {
      if (a.status !== b.status) {
        return a.status === 'OPEN' ? -1 : 1;
      }
      return Date.parse(b.createdAt) - Date.parse(a.createdAt);
    }),
  );
}
