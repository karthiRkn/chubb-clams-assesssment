import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ClaimList } from '../../../shared/claim-list/claim-list';
import { PageHeader } from '../../../shared/page-header/page-header';

/** Claims table: GET /claims (scoped by the backend to the claimant's own claims), with filters and paging. */
@Component({
  selector: 'app-my-claims',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, RouterLink, ClaimList, PageHeader],
  template: `
    <div class="page">
      <app-page-header title="My claims" subtitle="Every claim you have submitted, newest first.">
        <a mat-flat-button routerLink="/claimant/new-claim"><mat-icon>add</mat-icon>New claim</a>
      </app-page-header>

      <app-claim-list
        source="claims"
        emptyTitle="You have not submitted a claim yet"
        emptyMessage="When you report an incident it will show up here, with its status."
      >
        <a mat-flat-button routerLink="/claimant/new-claim"><mat-icon>add</mat-icon>Report a claim</a>
      </app-claim-list>
    </div>
  `,
})
export class MyClaims {}
