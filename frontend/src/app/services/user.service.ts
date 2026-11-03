import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API } from '../config/api.config';
import {
  ChangeRoleRequest,
  CreateUserRequest,
  PageResponse,
  Role,
  SetActiveRequest,
  User,
} from '../models';
import { toHttpParams } from '../utils/http-params';

/** User Service (:8185). Everything except `me()` is ADMIN-only on the backend. */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);
  private readonly base = `${API.user}/users`;

  /** GET /users/me - the caller's own record (any role). */
  me(): Observable<User> {
    return this.http.get<User>(`${this.base}/me`);
  }

  /** POST /users - create a user with any role. */
  create(request: CreateUserRequest): Observable<User> {
    return this.http.post<User>(this.base, request);
  }

  /** GET /users - ordered by username; page is zero-based, size 1-100. */
  list(page = 0, size = 20): Observable<PageResponse<User>> {
    return this.http.get<PageResponse<User>>(this.base, { params: toHttpParams({ page, size }) });
  }

  /** GET /users/{username} */
  get(username: string): Observable<User> {
    return this.http.get<User>(`${this.base}/${encodeURIComponent(username)}`);
  }

  /** PUT /users/{username}/role - 409 CANNOT_CHANGE_OWN_ROLE for the caller's own account. */
  changeRole(username: string, role: Role): Observable<User> {
    const body: ChangeRoleRequest = { role };
    return this.http.put<User>(`${this.base}/${encodeURIComponent(username)}/role`, body);
  }

  /** PUT /users/{username}/active - 409 CANNOT_DEACTIVATE_SELF for the caller's own account. */
  setActive(username: string, active: boolean): Observable<User> {
    const body: SetActiveRequest = { active };
    return this.http.put<User>(`${this.base}/${encodeURIComponent(username)}/active`, body);
  }
}
