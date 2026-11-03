import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API } from '../config/api.config';
import { SKIP_ERROR_TOAST, SKIP_LOADING } from '../interceptors/http-context';
import {
  AssignClaimRequest,
  Claim,
  ClaimAssignmentEntry,
  ClaimHistoryEntry,
  ClaimListParams,
  ClaimSummary,
  ClaimUpdateEntry,
  CreateClaimRequest,
  PageResponse,
  UpdateClaimRequest,
  WorkflowResponse,
} from '../models';
import { toHttpParams } from '../utils/http-params';

const quietContext = () => new HttpContext().set(SKIP_LOADING, true).set(SKIP_ERROR_TOAST, true);

/**
 * Claim Service (:8181).
 * What a caller sees is decided by the backend from the token: a claimant their own claims, a claims officer the unassigned
 * queue plus their own, a manager every claim. A claim outside that scope answers 404, exactly like an unknown one.
 */
@Injectable({ providedIn: 'root' })
export class ClaimService {
  private readonly http = inject(HttpClient);
  private readonly claims = `${API.claim}/claims`;
  private readonly officerClaims = `${API.claim}/officer/claims`;

  /** POST /claims (CLAIMANT) - the server generates claimId and sets the claimant from the token. */
  create(request: CreateClaimRequest): Observable<Claim> {
    return this.http.post<Claim>(this.claims, request);
  }

  /**
   * GET /claims - newest first. Claimants use this for "My claims".
   * `quiet` is for background lookups (badges, counts): no global progress bar and no error toast.
   */
  list(params: ClaimListParams = {}, quiet = false): Observable<PageResponse<ClaimSummary>> {
    return this.http.get<PageResponse<ClaimSummary>>(this.claims, {
      params: toHttpParams(params),
      context: quiet ? quietContext() : undefined,
    });
  }

  /** GET /officer/claims - the staff view of the same list (CLAIMS_OFFICER, MANAGER). */
  listStaff(params: ClaimListParams = {}, quiet = false): Observable<PageResponse<ClaimSummary>> {
    return this.http.get<PageResponse<ClaimSummary>>(this.officerClaims, {
      params: toHttpParams(params),
      context: quiet ? quietContext() : undefined,
    });
  }

  /** GET /claims/{claimId} */
  get(claimId: string): Observable<Claim> {
    return this.http.get<Claim>(`${this.claims}/${encodeURIComponent(claimId)}`);
  }

  /** PATCH /claims/{claimId} - only while SUBMITTED (409 CLAIM_NOT_EDITABLE afterwards). Send only what changes. */
  update(claimId: string, changes: UpdateClaimRequest): Observable<Claim> {
    return this.http.patch<Claim>(`${this.claims}/${encodeURIComponent(claimId)}`, changes);
  }

  /** GET /claims/{claimId}/history - every status change: from, to, when, who. */
  history(claimId: string): Observable<ClaimHistoryEntry[]> {
    return this.http.get<ClaimHistoryEntry[]>(`${this.claims}/${encodeURIComponent(claimId)}/history`);
  }

  /** GET /claims/{claimId}/assignments - every change of officer. */
  assignments(claimId: string): Observable<ClaimAssignmentEntry[]> {
    return this.http.get<ClaimAssignmentEntry[]>(`${this.claims}/${encodeURIComponent(claimId)}/assignments`);
  }

  /** GET /claims/{claimId}/updates - the claimant's own edits. */
  updates(claimId: string): Observable<ClaimUpdateEntry[]> {
    return this.http.get<ClaimUpdateEntry[]>(`${this.claims}/${encodeURIComponent(claimId)}/updates`);
  }

  /**
   * POST /officer/claims/{claimId}/assign
   * A manager may name any active claims officer (400 INVALID_OFFICER otherwise); an officer only themselves
   * (403 ASSIGNMENT_NOT_PERMITTED for anyone else).
   */
  assign(claimId: string, officerId: string): Observable<WorkflowResponse> {
    const body: AssignClaimRequest = { officerId };
    return this.http.post<WorkflowResponse>(`${this.officerClaims}/${encodeURIComponent(claimId)}/assign`, body);
  }
}
