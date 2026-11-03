import { TestBed } from '@angular/core/testing';
import { InformationRequest } from '../../models';
import { InformationRequestsTable } from './information-requests-table';

const request = (over: Partial<InformationRequest>): InformationRequest => ({
  informationRequestId: 'IR-2026-000001',
  claimId: 'CLM-2026-000101',
  question: 'Please send photos.',
  status: 'OPEN',
  requestedByOfficerId: 'officer1',
  createdAt: '2026-10-27T08:00:00Z',
  answer: null,
  answeredAt: null,
  ...over,
});

function render(requests: InformationRequest[]): HTMLElement {
  const fixture = TestBed.createComponent(InformationRequestsTable);
  fixture.componentRef.setInput('requests', requests);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('InformationRequestsTable', () => {
  it('shows "No information requests" when there are none', () => {
    const root = render([]);
    expect(text(root)).toContain('No information requests');
    expect(root.querySelector('table')).toBeNull();
  });

  it('has the six columns, in order', () => {
    const root = render([request({})]);
    const headers = [...root.querySelectorAll('th')].map((th) => text(th));
    expect(headers).toEqual(['Request ID', 'Question', 'Status', 'Created', 'Answer', 'Answered']);
  });

  it('shows an open request as waiting, with no answer date', () => {
    const root = render([request({})]);
    const cells = [...root.querySelectorAll('tbody td')].map((td) => text(td));
    expect(cells[0]).toBe('IR-2026-000001');
    expect(cells[1]).toBe('Please send photos.');
    expect(cells[2]).toBe('hourglass_top Open');
    expect(cells[4]).toBe('Awaiting answer');
    expect(cells[5]).toBe('—');
  });

  it('shows an answered request with its answer and date', () => {
    const root = render([request({ status: 'ANSWERED', answer: 'Attached.', answeredAt: '2026-10-28T09:30:00Z' })]);
    const cells = [...root.querySelectorAll('tbody td')].map((td) => text(td));
    expect(cells[2]).toBe('check_circle Answered');
    expect(cells[4]).toBe('Attached.');
    expect(cells[5]).toContain('2026');
  });

  it('lists open requests first, then the newest', () => {
    const root = render([
      request({ informationRequestId: 'IR-A', status: 'ANSWERED', createdAt: '2026-10-29T00:00:00Z', answer: 'x', answeredAt: '2026-10-30T00:00:00Z' }),
      request({ informationRequestId: 'IR-B', status: 'OPEN', createdAt: '2026-10-01T00:00:00Z' }),
      request({ informationRequestId: 'IR-C', status: 'ANSWERED', createdAt: '2026-10-05T00:00:00Z', answer: 'y', answeredAt: '2026-10-06T00:00:00Z' }),
    ]);
    const ids = [...root.querySelectorAll('tbody tr')].map((tr) => text(tr.querySelector('td')));
    expect(ids).toEqual(['IR-B', 'IR-A', 'IR-C']);
  });
});
