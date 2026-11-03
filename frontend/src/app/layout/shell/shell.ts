import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, effect, ElementRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSidenavContent, MatSidenavModule } from '@angular/material/sidenav';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../../services/auth.service';
import { ClaimService } from '../../services/claim.service';
import { LoadingService } from '../../services/loading.service';
import { NotificationService } from '../../services/notification.service';
import { Footer } from '../footer/footer';
import { Header } from '../header/header';
import { Sidebar } from '../sidebar/sidebar';

/** Tablet and below: the navigation becomes an overlay drawer. */
const COMPACT_QUERY = '(max-width: 959px)';

/**
 * The frame around every signed-in page: header, role-based sidebar, footer, notification bell and a global progress bar.
 * Desktop shows the sidebar beside the page; tablet and mobile show it as a drawer that closes after a link is chosen.
 */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatProgressBarModule, MatSidenavModule, RouterOutlet, Header, Sidebar, Footer],
  template: `
    <div class="shell">
      <button type="button" class="skip-link" (click)="focusMain()">Skip to main content</button>
      <app-header (menuToggle)="navOpen.set(!navOpen())" />

      <div class="progress" aria-hidden="true">
        @if (loading.isLoading()) {
          <mat-progress-bar mode="indeterminate" />
        }
      </div>

      <mat-sidenav-container class="container">
        <mat-sidenav
          class="sidenav"
          [mode]="compact() ? 'over' : 'side'"
          [opened]="navOpen()"
          (closedStart)="navOpen.set(false)"
          [attr.aria-label]="'Navigation'"
        >
          <app-sidebar (navigated)="onNavigated()" />
        </mat-sidenav>

        <mat-sidenav-content class="content">
          <main #main id="main" class="main" tabindex="-1">
            <router-outlet />
          </main>
          <app-footer />
        </mat-sidenav-content>
      </mat-sidenav-container>
    </div>
  `,
  styles: `
    .shell {
      display: flex;
      flex-direction: column;
      height: 100vh;
      height: 100dvh;
    }
    .progress {
      position: relative;
      z-index: 3;
      height: 4px;
      margin-bottom: -4px;
    }
    .container {
      flex: 1;
      min-height: 0;
      background: var(--mat-sys-surface);
    }
    .sidenav {
      width: 264px;
      max-width: 85vw;
    }
    .content {
      display: flex;
      flex-direction: column;
    }
    .main {
      flex: 1 0 auto;
      outline: none;
    }
    .skip-link {
      position: absolute;
      left: 8px;
      top: -48px;
      z-index: 10;
      padding: 8px 16px;
      border: 0;
      border-radius: 8px;
      background: var(--mat-sys-primary);
      color: var(--mat-sys-on-primary);
      font: var(--mat-sys-label-large);
      cursor: pointer;
    }
    .skip-link:focus {
      top: 8px;
    }
  `,
})
export class Shell {
  private readonly breakpoints = inject(BreakpointObserver);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly claims = inject(ClaimService);
  private readonly notifications = inject(NotificationService);
  protected readonly loading = inject(LoadingService);

  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');
  private readonly content = viewChild.required(MatSidenavContent);

  protected readonly compact = toSignal(this.breakpoints.observe(COMPACT_QUERY).pipe(map((state) => state.matches)), {
    initialValue: this.breakpoints.isMatched(COMPACT_QUERY),
  });
  protected readonly navOpen = signal(!this.breakpoints.isMatched(COMPACT_QUERY));

  constructor() {
    // Open the sidebar on desktop, tuck it away on smaller screens whenever the screen size class changes.
    effect(() => this.navOpen.set(!this.compact()));

    // The page scrolls inside the sidenav content, not the window, so scroll to the top on every navigation ourselves.
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.content().scrollTo({ top: 0 }));

    this.checkAttention();
  }

  protected onNavigated(): void {
    if (this.compact()) {
      this.navOpen.set(false);
    }
  }

  protected focusMain(): void {
    this.main().nativeElement.focus();
  }

  /**
   * Seeds the notification bell with what needs this person's attention right now.
   * Quiet calls: a failure here must not toast or flash the progress bar. (Server-pushed notifications are a planned
   * enhancement; see CLAUDE.md.)
   */
  private checkAttention(): void {
    const plural = (n: number, one: string, many: string) => (n === 1 ? one : many);

    switch (this.auth.role()) {
      case 'CLAIMANT':
        this.claims.list({ status: 'MORE_INFO_REQUIRED', size: 1 }, true).subscribe({
          next: (page) => {
            if (page.totalElements > 0) {
              const n = page.totalElements;
              this.notifications.push(
                'warning',
                `${n} ${plural(n, 'claim needs', 'claims need')} more information from you. Open "Information requests" to answer.`,
                false,
              );
            }
          },
          error: () => undefined,
        });
        break;
      case 'CLAIMS_OFFICER':
      case 'MANAGER': {
        const manager = this.auth.role() === 'MANAGER';
        this.claims.listStaff({ assigned: false, size: 1 }, true).subscribe({
          next: (page) => {
            if (page.totalElements > 0) {
              const n = page.totalElements;
              this.notifications.push(
                'info',
                manager
                  ? `${n} ${plural(n, 'claim is', 'claims are')} waiting to be assigned to an officer.`
                  : `${n} unassigned ${plural(n, 'claim is', 'claims are')} waiting in the queue.`,
                false,
              );
            }
          },
          error: () => undefined,
        });
        break;
      }
      default:
        break;
    }
  }
}
