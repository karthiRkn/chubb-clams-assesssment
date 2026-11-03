import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { InformationRequest } from '../../models';
import { EmptyState } from '../empty-state/empty-state';
import { InformationRequestItem } from './information-request-item';

/** A claim's information requests, open ones first. Re-emits `answered` so the page can reload the claim. */
@Component({
  selector: 'app-information-request-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [InformationRequestItem, EmptyState],
  template: `
    @if (requests().length === 0) {
      <app-empty-state
        icon="forum"
        title="No information requests"
        [message]="emptyMessage()"
      />
    } @else {
      <div class="list">
        @for (request of sorted(); track request.informationRequestId) {
          <app-information-request-item
            [request]="request"
            [canAnswer]="canAnswer()"
            (answered)="answered.emit()"
          />
        }
      </div>
    }
  `,
  styles: `
    .list {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
  `,
})
export class InformationRequestList {
  readonly requests = input.required<readonly InformationRequest[]>();
  readonly canAnswer = input(false);
  readonly emptyMessage = input('The claims officer has not asked for anything more.');
  readonly answered = output<void>();

  protected readonly sorted = computed(() =>
    [...this.requests()].sort((a, b) => {
      if (a.status !== b.status) {
        return a.status === 'OPEN' ? -1 : 1;
      }
      return Date.parse(b.createdAt) - Date.parse(a.createdAt);
    }),
  );
}
