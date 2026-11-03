import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { ClaimAssignmentEntry, ClaimHistoryEntry, ClaimUpdateEntry } from '../../models';
import { StatusChip } from '../status-chip/status-chip';

/**
 * The three histories of a claim, one tab each (all newest first):
 *  - Status history      GET /claims/{id}/history       the claim's status timeline: from, to, when, who
 *  - Assignment history  GET /claims/{id}/assignments   every change of officer
 *  - Update history      GET /claims/{id}/updates       the claimant's own edits (free text is only reported as "changed")
 */
@Component({
  selector: 'app-claim-history-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatIconModule, MatTabsModule, StatusChip],
  template: `
    <mat-tab-group animationDuration="0ms" mat-align-tabs="start" aria-label="Claim history">
      <mat-tab label="Status history">
        <div class="tab">
          @if (status().length === 0) {
            <p class="muted empty">No status changes recorded.</p>
          } @else {
            <ol class="timeline" aria-label="Status timeline">
              @for (entry of status(); track $index) {
                <li class="entry">
                  <span class="dot" aria-hidden="true"></span>
                  <div class="body">
                    <div class="title">
                      @if (entry.fromStatus === null) {
                        <strong>Claim submitted</strong>
                      } @else {
                        <app-status-chip [status]="entry.fromStatus" />
                        <mat-icon aria-label="changed to">arrow_forward</mat-icon>
                        <app-status-chip [status]="entry.toStatus" />
                      }
                    </div>
                    <div class="meta muted">{{ entry.changedAt | date: 'medium' }}@if (entry.actor) { · {{ entry.actor }} }</div>
                  </div>
                </li>
              }
            </ol>
          }
        </div>
      </mat-tab>

      <mat-tab label="Assignment history">
        <div class="tab">
          @if (assignmentList().length === 0) {
            <p class="muted empty">This claim has not been assigned to an officer yet.</p>
          } @else {
            <ol class="timeline" aria-label="Assignment history">
              @for (entry of assignmentList(); track $index) {
                <li class="entry">
                  <span class="dot" aria-hidden="true"></span>
                  <div class="body">
                    <div class="title">
                      @if (entry.previousOfficerId) {
                        <span>{{ entry.previousOfficerId }}</span>
                        <mat-icon aria-label="reassigned to">arrow_forward</mat-icon>
                      } @else {
                        <span class="muted">Unassigned</span>
                        <mat-icon aria-label="assigned to">arrow_forward</mat-icon>
                      }
                      <strong>{{ entry.newOfficerId ?? 'Unassigned' }}</strong>
                    </div>
                    <div class="meta muted">{{ entry.changedAt | date: 'medium' }}@if (entry.actor) { · by {{ entry.actor }} }</div>
                  </div>
                </li>
              }
            </ol>
          }
        </div>
      </mat-tab>

      <mat-tab label="Update history">
        <div class="tab">
          @if (updateList().length === 0) {
            <p class="muted empty">The claimant has not changed any details since submitting.</p>
          } @else {
            <ol class="timeline" aria-label="Update history">
              @for (entry of updateList(); track $index) {
                <li class="entry">
                  <span class="dot" aria-hidden="true"></span>
                  <div class="body">
                    <div class="title"><strong>Details edited</strong></div>
                    <ul class="changes">
                      @for (change of entry.changes; track $index) {
                        <li>{{ change }}</li>
                      }
                    </ul>
                    <div class="meta muted">{{ entry.changedAt | date: 'medium' }}@if (entry.actor) { · {{ entry.actor }} }</div>
                  </div>
                </li>
              }
            </ol>
          }
        </div>
      </mat-tab>
    </mat-tab-group>
  `,
  styles: `
    .tab {
      padding: 16px 4px 4px;
    }
    .empty {
      padding: 16px 0;
    }
    .timeline {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .entry {
      position: relative;
      display: flex;
      gap: 16px;
      padding: 0 0 20px;
    }
    .entry:last-child {
      padding-bottom: 0;
    }
    .entry:not(:last-child)::before {
      content: '';
      position: absolute;
      top: 14px;
      bottom: -2px;
      left: 5px;
      width: 2px;
      background: var(--app-border);
    }
    .dot {
      flex: none;
      width: 12px;
      height: 12px;
      margin-top: 4px;
      border-radius: 50%;
      background: var(--mat-sys-primary);
      box-shadow: 0 0 0 3px var(--mat-sys-primary-container);
    }
    .body {
      min-width: 0;
    }
    .title {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
    }
    .title mat-icon {
      width: 18px;
      height: 18px;
      font-size: 18px;
      color: var(--app-muted);
    }
    .meta {
      margin-top: 2px;
      font: var(--mat-sys-body-small);
    }
    .changes {
      margin: 4px 0 0;
      padding-left: 18px;
      overflow-wrap: anywhere;
    }
  `,
})
export class ClaimHistoryTabs {
  readonly history = input.required<readonly ClaimHistoryEntry[]>();
  readonly assignments = input.required<readonly ClaimAssignmentEntry[]>();
  readonly updates = input.required<readonly ClaimUpdateEntry[]>();

  protected readonly status = computed(() => newestFirst(this.history()));
  protected readonly assignmentList = computed(() => newestFirst(this.assignments()));
  protected readonly updateList = computed(() => newestFirst(this.updates()));
}

function newestFirst<T extends { changedAt: string }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => Date.parse(b.changedAt) - Date.parse(a.changedAt));
}
