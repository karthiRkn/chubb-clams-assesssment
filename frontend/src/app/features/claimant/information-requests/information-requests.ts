import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { forkJoin, map, of, switchMap } from 'rxjs';
import { ClaimSummary, InformationRequest } from '../../../models';
import { AssessmentService } from '../../../services/assessment.service';
import { ClaimService } from '../../../services/claim.service';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { InformationRequestList } from '../../../shared/information-requests/information-request-list';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';
import { StatusChip } from '../../../shared/status-chip/status-chip';
import { errorMessage } from '../../../utils/error-message';
import { MoneyPipe } from '../../../shared/money.pipe';

interface ClaimWithRequests {
  claim: ClaimSummary;
  requests: InformationRequest[];
}

/**
 * Everything officers are waiting on you for. Finds the claims in MORE_INFO_REQUIRED (GET /claims?status=...) and loads
 * each one's requests (GET /claims/{id}/information-requests) so they can be answered in one place.
 */
@Component({
  selector: 'app-claimant-information-requests',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MoneyPipe,
    MatButtonModule,
    MatIconModule,
    RouterLink,
    EmptyState,
    InformationRequestList,
    LoadingSpinner,
    PageHeader,
    StatusChip,
  ],
  template: `
    <div class="page">
      <app-page-header
        title="Information requests"
        subtitle="Questions from the claims team about your claims. Answer them to keep your claim moving."
      >
        <button mat-stroked-button type="button" (click)="load()"><mat-icon>refresh</mat-icon>Refresh</button>
      </app-page-header>

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load your requests" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (items(); as list) {
        @if (list.length === 0) {
          <app-empty-state
            icon="task_alt"
            title="Nothing is waiting on you"
            message="When an officer needs more information about one of your claims, the question will appear here."
          >
            <a mat-flat-button routerLink="/claimant/claims">View my claims</a>
          </app-empty-state>
        } @else {
          @for (item of list; track item.claim.claimId) {
            <section class="panel" [attr.aria-label]="'Claim ' + item.claim.claimId">
              <div class="claim-head">
                <div>
                  <a class="mono ref" [routerLink]="['/claimant/claims', item.claim.claimId]">{{ item.claim.claimId }}</a>
                  <div class="muted">
                    {{ item.claim.claimType === 'MOTOR' ? 'Motor' : 'Property' }} ·
                    {{ item.claim.claimedAmount | money: item.claim.currency }}
                  </div>
                </div>
                <app-status-chip [status]="item.claim.status" />
              </div>
              <app-information-request-list [requests]="item.requests" [canAnswer]="true" (answered)="load()" />
            </section>
          }
        }
      } @else {
        <app-loading-spinner label="Looking for requests…" />
      }
    </div>
  `,
  styles: `
    .claim-head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      margin-bottom: 16px;
    }
    .ref {
      font: var(--mat-sys-title-medium);
    }
  `,
})
export class ClaimantInformationRequests implements OnInit {
  private readonly claims = inject(ClaimService);
  private readonly assessment = inject(AssessmentService);

  protected readonly items = signal<ClaimWithRequests[] | null>(null);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.claims
      .list({ status: 'MORE_INFO_REQUIRED', size: 100 })
      .pipe(
        switchMap((page) =>
          page.content.length === 0
            ? of<ClaimWithRequests[]>([])
            : forkJoin(
                page.content.map((claim) =>
                  this.assessment.informationRequests(claim.claimId).pipe(map((requests) => ({ claim, requests }))),
                ),
              ),
        ),
      )
      .subscribe({
        next: (items) => this.items.set(items),
        error: (err: unknown) => this.error.set(errorMessage(err)),
      });
  }
}
