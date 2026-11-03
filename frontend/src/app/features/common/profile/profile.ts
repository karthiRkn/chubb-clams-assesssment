import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ROLE_LABELS, User } from '../../../models';
import { AuthService } from '../../../services/auth.service';
import { UserService } from '../../../services/user.service';
import { errorMessage } from '../../../utils/error-message';
import { EmptyState } from '../../../shared/empty-state/empty-state';
import { LoadingSpinner } from '../../../shared/loading-spinner/loading-spinner';
import { PageHeader } from '../../../shared/page-header/page-header';

/** The signed-in person's own record (GET /users/me) and the state of their session. Available to every role. */
@Component({
  selector: 'app-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatButtonModule, MatIconModule, EmptyState, LoadingSpinner, PageHeader],
  template: `
    <div class="page narrow">
      <app-page-header title="Profile" subtitle="Your account and current session." />

      @if (error(); as message) {
        <app-empty-state icon="error_outline" title="Could not load your profile" [message]="message">
          <button mat-flat-button type="button" (click)="load()">Try again</button>
        </app-empty-state>
      } @else if (user(); as u) {
        <section class="panel" aria-label="Account">
          <div class="identity">
            <mat-icon class="avatar" aria-hidden="true">account_circle</mat-icon>
            <div>
              <div class="name">{{ u.username }}</div>
              <div class="muted">{{ roleLabel() }}</div>
            </div>
          </div>
          <dl class="grid">
            <div>
              <dt>Username</dt>
              <dd class="mono">{{ u.username }}</dd>
            </div>
            <div>
              <dt>Role</dt>
              <dd>{{ roleLabel() }}</dd>
            </div>
            <div>
              <dt>Account status</dt>
              <dd>{{ u.active ? 'Active' : 'Inactive' }}</dd>
            </div>
            <div>
              <dt>Member since</dt>
              <dd>{{ u.createdAt | date: 'longDate' }}</dd>
            </div>
          </dl>
        </section>

        <section class="panel" aria-label="Session">
          <h2>Session</h2>
          <p class="muted">
            You are signed in until {{ expiresAt() | date: 'shortTime' }}. After that you will be asked to sign in again; the token
            is kept only for this browser tab.
          </p>
          <div class="actions">
            <button mat-stroked-button type="button" (click)="auth.logout()">
              <mat-icon>logout</mat-icon>
              Sign out
            </button>
          </div>
        </section>
      } @else {
        <app-loading-spinner label="Loading your profile…" />
      }
    </div>
  `,
  styles: `
    .narrow {
      max-width: 760px;
    }
    .identity {
      display: flex;
      align-items: center;
      gap: 16px;
      margin-bottom: 20px;
    }
    .avatar {
      width: 56px;
      height: 56px;
      font-size: 56px;
      color: var(--mat-sys-primary);
    }
    .name {
      font: var(--mat-sys-title-large);
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 16px 24px;
      margin: 0;
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--app-muted);
      margin-bottom: 2px;
    }
    dd {
      margin: 0;
    }
  `,
})
export class Profile implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly users = inject(UserService);

  protected readonly user = signal<User | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly roleLabel = computed(() => {
    const role = this.user()?.role ?? this.auth.role();
    return role ? ROLE_LABELS[role] : '';
  });
  protected readonly expiresAt = computed(() => this.auth.session()?.expiresAt ?? null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.error.set(null);
    this.users.me().subscribe({
      next: (user) => this.user.set(user),
      error: (err: unknown) => this.error.set(errorMessage(err)),
    });
  }
}
