import { Role } from './common.models';

/** POST /api/v1/auth/login (User Service, :8185) */
export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: 'Bearer';
  /** Seconds until the token expires (3600 by default). */
  expiresIn: number;
  username: string;
  role: Role;
}

/** The session kept in sessionStorage: the login response plus an absolute expiry. */
export interface AuthSession {
  accessToken: string;
  username: string;
  role: Role;
  /** Epoch milliseconds at which the token stops being accepted. */
  expiresAt: number;
}

/** POST /api/v1/auth/register - always creates a CLAIMANT. */
export interface RegisterRequest {
  username: string;
  password: string;
}

/** POST /api/v1/users (ADMIN) */
export interface CreateUserRequest {
  username: string;
  password: string;
  role: Role;
}

/** PUT /api/v1/users/{username}/role (ADMIN) */
export interface ChangeRoleRequest {
  role: Role;
}

/** PUT /api/v1/users/{username}/active (ADMIN) */
export interface SetActiveRequest {
  active: boolean;
}

/** Never carries a password or hash. */
export interface User {
  username: string;
  role: Role;
  active: boolean;
  createdAt: string;
}

/** Username rule shared by register and create-user: 3-50 chars, lower-case letters, digits, '.', '_' or '-', starting with a letter or digit. */
export const USERNAME_PATTERN = /^[a-z0-9][a-z0-9._-]{2,49}$/;
export const USERNAME_HINT =
  '3-50 characters: lower-case letters, digits, ".", "_" or "-", starting with a letter or digit';
