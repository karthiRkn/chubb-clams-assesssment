import { computed, inject, Injectable, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

export type NotificationType = 'success' | 'error' | 'warning' | 'info';

export interface AppNotification {
  id: number;
  type: NotificationType;
  message: string;
  createdAt: Date;
  read: boolean;
}

const MAX_KEPT = 50;
const DURATION_MS: Record<NotificationType, number> = { success: 4000, info: 4000, warning: 6000, error: 7000 };

/**
 * Toast notifications plus the list behind the header's notification area.
 *
 * Every toast is also kept (newest first, last 50) so a message that disappeared before it was read can be found again.
 * This is purely client-side for now; server-pushed notifications (the Kafka enhancement in CLAUDE.md) would call
 * `push()` from here without touching any component.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly snackBar = inject(MatSnackBar);
  private nextId = 1;

  private readonly _items = signal<AppNotification[]>([]);
  readonly items = this._items.asReadonly();
  readonly unreadCount = computed(() => this._items().filter((n) => !n.read).length);

  success(message: string): void {
    this.push('success', message);
  }

  error(message: string): void {
    this.push('error', message);
  }

  warning(message: string): void {
    this.push('warning', message);
  }

  info(message: string): void {
    this.push('info', message);
  }

  /** Shows a toast and records it. Pass `toast: false` to record a message without interrupting the user. */
  push(type: NotificationType, message: string, toast = true): void {
    const item: AppNotification = { id: this.nextId++, type, message, createdAt: new Date(), read: false };
    this._items.update((items) => [item, ...items].slice(0, MAX_KEPT));
    if (toast) {
      this.snackBar.open(message, 'Dismiss', {
        duration: DURATION_MS[type],
        panelClass: ['toast', `toast-${type}`],
        horizontalPosition: 'end',
        verticalPosition: 'bottom',
      });
    }
  }

  markAllRead(): void {
    this._items.update((items) => items.map((n) => (n.read ? n : { ...n, read: true })));
  }

  clear(): void {
    this._items.set([]);
  }
}
