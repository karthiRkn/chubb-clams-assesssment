import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { NotificationService, NotificationType } from '../../services/notification.service';

const ICONS: Record<NotificationType, string> = {
  success: 'check_circle',
  error: 'error',
  warning: 'warning',
  info: 'info',
};

/**
 * The bell in the header. Everything that was shown as a toast, plus "needs your attention" notices, is listed here so
 * nothing is lost when a toast disappears. Opening the panel marks the notifications as read.
 */
@Component({
  selector: 'app-notification-area',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, MatBadgeModule, MatButtonModule, MatIconModule, MatMenuModule],
  template: `
    <button
      mat-icon-button
      type="button"
      [matMenuTriggerFor]="panel"
      (menuOpened)="notifications.markAllRead()"
      [attr.aria-label]="
        notifications.unreadCount() > 0 ? 'Notifications, ' + notifications.unreadCount() + ' unread' : 'Notifications'
      "
    >
      <mat-icon [matBadge]="notifications.unreadCount()" [matBadgeHidden]="notifications.unreadCount() === 0" matBadgeSize="small" aria-hidden="true">notifications</mat-icon>
    </button>

    <mat-menu #panel="matMenu" class="notification-menu" xPosition="before">
      <div class="panel" (click)="$event.stopPropagation()" (keydown)="$event.stopPropagation()" tabindex="-1">
        <div class="panel-head">
          <strong>Notifications</strong>
          @if (notifications.items().length > 0) {
            <button mat-button type="button" (click)="notifications.clear()">Clear all</button>
          }
        </div>
        @if (notifications.items().length === 0) {
          <p class="empty muted">You are all caught up.</p>
        } @else {
          <ul class="list">
            @for (item of notifications.items(); track item.id) {
              <li class="item" [class]="item.type">
                <mat-icon aria-hidden="true">{{ icon(item.type) }}</mat-icon>
                <div>
                  <div class="message">{{ item.message }}</div>
                  <div class="time muted">{{ item.createdAt | date: 'shortTime' }}</div>
                </div>
              </li>
            }
          </ul>
        }
      </div>
    </mat-menu>
  `,
  styles: `
    .panel {
      width: min(380px, 92vw);
      max-height: 60vh;
      overflow-y: auto;
    }
    .panel-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 16px;
      border-bottom: 1px solid var(--app-border);
    }
    .empty {
      padding: 24px 16px;
      text-align: center;
    }
    .list {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .item {
      display: flex;
      gap: 12px;
      padding: 12px 16px;
      border-bottom: 1px solid var(--app-border);
    }
    .item:last-child {
      border-bottom: 0;
    }
    .message {
      overflow-wrap: anywhere;
    }
    .time {
      font: var(--mat-sys-body-small);
    }
    .success mat-icon { color: var(--status-approved-fg); }
    .error mat-icon { color: var(--status-rejected-fg); }
    .warning mat-icon { color: var(--status-info-fg); }
    .info mat-icon { color: var(--status-submitted-fg); }
  `,
})
export class NotificationArea {
  protected readonly notifications = inject(NotificationService);

  protected icon(type: NotificationType): string {
    return ICONS[type];
  }
}
