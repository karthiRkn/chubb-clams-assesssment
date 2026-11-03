import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API } from '../config/api.config';
import { SKIP_ERROR_TOAST } from '../interceptors/http-context';
import {
  AnswerResponse,
  AssessmentEntry,
  CreateInformationRequest,
  DecisionRequest,
  DecisionResponse,
  InformationRequest,
  ReviewRequest,
  SettlementRequest,
  SettlementResponse,
  SubmitInformationRequest,
  WorkflowResponse,
} from '../models';

/**
 * Assessment Service (:8182): review, assessment history, decision, settlement and information requests.
 * The acting officer is always the token's user, so no officer ID is ever sent.
 */
@Injectable({ providedIn: 'root' })
export class AssessmentService {
  private readonly http = inject(HttpClient);
  private readonly officerClaims = `${API.assessment}/officer/claims`;
  private readonly claims = `${API.assessment}/claims`;

  /** POST /officer/claims/{claimId}/review - SUBMITTED or MORE_INFO_REQUIRED to UNDER_REVIEW; takes an unassigned claim. */
  startReview(claimId: string, request: ReviewRequest = {}): Observable<WorkflowResponse> {
    return this.http.post<WorkflowResponse>(`${this.officerClaims}/${encodeURIComponent(claimId)}/review`, request);
  }

  /** GET /officer/claims/{claimId}/assessments - oldest first. Officers only; never shown to claimants. */
  assessments(claimId: string): Observable<AssessmentEntry[]> {
    return this.http.get<AssessmentEntry[]>(`${this.officerClaims}/${encodeURIComponent(claimId)}/assessments`);
  }

  /** POST /officer/claims/{claimId}/decision - approve (with amount) or reject (with reason). UNDER_REVIEW only. */
  decide(claimId: string, request: DecisionRequest): Observable<DecisionResponse> {
    return this.http.post<DecisionResponse>(`${this.officerClaims}/${encodeURIComponent(claimId)}/decision`, request);
  }

  /** POST /officer/claims/{claimId}/settlement - APPROVED to SETTLED. */
  settle(claimId: string, request: SettlementRequest = {}): Observable<SettlementResponse> {
    return this.http.post<SettlementResponse>(`${this.officerClaims}/${encodeURIComponent(claimId)}/settlement`, request);
  }

  /**
   * POST /officer/claims/{claimId}/information-requests - UNDER_REVIEW to MORE_INFO_REQUIRED.
   * Pass `inlineErrors` when the caller shows a failure itself (for example inside a dialog): no error toast is raised, but a 401
   * still ends the session and redirects to the login page.
   */
  requestInformation(
    claimId: string,
    request: CreateInformationRequest,
    options: { inlineErrors?: boolean } = {},
  ): Observable<InformationRequest> {
    return this.http.post<InformationRequest>(
      `${this.officerClaims}/${encodeURIComponent(claimId)}/information-requests`,
      request,
      { context: options.inlineErrors ? new HttpContext().set(SKIP_ERROR_TOAST, true) : undefined },
    );
  }

  /** GET /claims/{claimId}/information-requests - the requests and their answers (claimant own, officer visible, manager). */
  informationRequests(claimId: string): Observable<InformationRequest[]> {
    return this.http.get<InformationRequest[]>(`${this.claims}/${encodeURIComponent(claimId)}/information-requests`);
  }

  /** POST /claims/{claimId}/information (CLAIMANT) - answers one request; the claim resumes when none is left open. */
  answerInformation(claimId: string, request: SubmitInformationRequest): Observable<AnswerResponse> {
    return this.http.post<AnswerResponse>(`${this.claims}/${encodeURIComponent(claimId)}/information`, request);
  }
}
