import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { ROLE_LABELS } from '../../models';
import { AuthService } from '../../services/auth.service';
import { NotificationArea } from '../notification-area/notification-area';

@Component({
  selector: 'app-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDividerModule, MatIconModule, MatMenuModule, MatToolbarModule, RouterLink, NotificationArea],
  template: `
    <mat-toolbar class="toolbar" role="banner">
      <button mat-icon-button type="button" (click)="menuToggle.emit()" aria-label="Toggle navigation menu">
        <mat-icon>menu</mat-icon>
      </button>

      <a class="brand" [routerLink]="home()" aria-label="Chubb APAC Claims, home">
        <mat-icon aria-hidden="true">shield</mat-icon>
        <span class="brand-text">Chubb APAC Claims</span>
      </a>

      <span class="spacer"></span>

      <app-notification-area />

      <button mat-button type="button" class="user" [matMenuTriggerFor]="userMenu" aria-label="Account menu">
        <mat-icon aria-hidden="true">account_circle</mat-icon>
        <span class="user-text">
          <span class="user-name">{{ auth.username() }}</span>
          <span class="user-role">{{ roleLabel() }}</span>
        </span>
        <mat-icon aria-hidden="true">arrow_drop_down</mat-icon>
      </button>
      <mat-menu #userMenu="matMenu" xPosition="before">
        <div class="menu-head" tabindex="-1">
          <div class="user-name">{{ auth.username() }}</div>
          <div class="muted">{{ roleLabel() }}</div>
        </div>
        <mat-divider />
        <a mat-menu-item routerLink="/profile"><mat-icon>person</mat-icon><span>Profile</span></a>
        <button mat-menu-item type="button" (click)="auth.logout()"><mat-icon>logout</mat-icon><span>Sign out</span></button>
      </mat-menu>
    </mat-toolbar>
  `,
  styles: `
    .toolbar {
      position: relative;
      z-index: 2;
      gap: 4px;
      padding: 0 8px;
      background: var(--mat-sys-primary);
      color: var(--mat-sys-on-primary);
      --mat-icon-button-icon-color: var(--mat-sys-on-primary);
      --mat-button-text-label-text-color: var(--mat-sys-on-primary);
    }
    .brand {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      color: inherit;
      text-decoration: none;
      font: var(--mat-sys-title-medium);
    }
    .user {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      height: 48px;
    }
    .user-text {
      display: inline-flex;
      flex-direction: column;
      align-items: flex-start;
      line-height: 1.2;
      text-align: left;
    }
    .user-name {
      font: var(--mat-sys-label-large);
    }
    .user-role {
      font: var(--mat-sys-body-small);
      opacity: 0.85;
    }
    .menu-head {
      padding: 12px 16px;
    }
    @media (max-width: 599px) {
      .brand-text,
      .user-text {
        display: none;
      }
    }
  `,
})
export class Header {
  protected readonly auth = inject(AuthService);

  readonly menuToggle = output<void>();

  protected readonly home = computed(() => this.auth.homeRoute());
  protected readonly roleLabel = computed(() => {
    const role = this.auth.role();
    return role ? ROLE_LABELS[role] : '';
  });
}
