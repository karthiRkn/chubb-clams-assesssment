import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../services/auth.service';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'app-not-found',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, EmptyState],
  template: `
    <div class="page">
      <app-empty-state
        icon="travel_explore"
        title="Page not found"
        message="The page you are looking for does not exist or has moved."
      >
        <a mat-flat-button [routerLink]="home()">Go to my home page</a>
      </app-empty-state>
    </div>
  `,
})
export class NotFound {
  private readonly auth = inject(AuthService);
  protected readonly home = computed(() => this.auth.homeRoute());
}
