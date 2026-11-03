import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API } from '../config/api.config';
import { ManagementSummary, OfficerMetrics } from '../models';

/** Management reports. Served by the Claim Service (:8181); MANAGER (and ADMIN) only. */
@Injectable({ providedIn: 'root' })
export class ManagementService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API.claim}/management`;

  /** GET /management/summary - outstanding claims and estimated liability per currency (never summed across currencies). */
  summary(): Observable<ManagementSummary> {
    return this.http.get<ManagementSummary>(`${this.base}/summary`);
  }

  /** GET /management/officers - per-officer open workload and decisions. */
  officers(): Observable<OfficerMetrics> {
    return this.http.get<OfficerMetrics>(`${this.base}/officers`);
  }
}
