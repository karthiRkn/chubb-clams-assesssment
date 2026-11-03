# CLAUDE.md

## Project Overview

Project: Chubb APAC Claims Management Frontend

This is an Angular 22 Single Page Application that integrates with three existing Spring Boot microservices:

1. User Service (Authentication & User Management)
2. Claim Service (Claim Lifecycle Management)
3. Assessment Service (Claim Review, Approval, Settlement)

The frontend must consume the existing backend APIs without modifying backend behavior.

---

# Core Principles

## Do Not Change Backend Contracts

The backend APIs, payloads, validation rules, status values, authorization model, and business logic are the source of truth.

Frontend responsibilities:

- Display data
- Validate obvious user input
- Route users
- Handle authentication state
- Present errors
- Call APIs

Frontend must NOT:

- Recalculate business decisions
- Invent workflows
- Create unsupported states
- Combine currencies
- Store officer information manually
- Modify JWT payloads

---

# Technology Stack

## Framework

Angular 22

Required features:

- Standalone Components
- Signals
- Computed Signals
- Signal Stores where appropriate
- Lazy Loaded Routes
- Zoneless Change Detection

## UI Library

Angular Material 22

Material 3 Design System

Required usage:

- MatToolbar
- MatSidenav
- MatTable
- MatPaginator
- MatDialog
- MatSnackBar
- MatTabs
- MatCard
- MatFormField
- MatSelect
- MatMenu

## Charts

Chart.js 4
ng2-charts 11

Used for:

- Manager Dashboard
- Officer Metrics
- Liability Reports

## Authentication

JWT Access Token

Storage:

sessionStorage

Never use:

- localStorage
- Cookies
- IndexedDB

Token Object:

{
accessToken,
username,
role,
expiresAt
}

---

# Backend Services

## User Service

Port:

8185

Development Prefix:

/svc/user

Purpose:

- Login
- Registration
- User Management
- Profile

---

## Claim Service

Port:

8181

Development Prefix:

/svc/claim

Purpose:

- Claim Submission
- Claim Management
- Claim History

---

## Assessment Service

Port:

8182

Development Prefix:

/svc/assessment

Purpose:

- Reviews
- Approvals
- Rejections
- Settlements
- Information Requests

---

# API Configuration

Location:

src/app/config/api.config.ts

Example:

export const API = {
user: '/svc/user',
claim: '/svc/claim',
assessment: '/svc/assessment'
};

Never hardcode URLs throughout components.

All requests must go through service classes.

---

# Required Folder Structure

src/
└── app/
├── core/
│   ├── guards/
│   ├── interceptors/
│   ├── layouts/
│   ├── services/
│   └── models/
│
├── shared/
│   ├── components/
│   ├── dialogs/
│   ├── pipes/
│   ├── directives/
│   └── utils/
│
├── features/
│   ├── auth/
│   ├── claimant/
│   ├── officer/
│   ├── manager/
│   └── admin/
│
├── config/
└── environments/

---

# Authentication Flow

## Login

POST

/api/v1/auth/login

User enters:

- Username
- Password

Successful response provides:

- JWT Token
- Username
- Role

Store:

sessionStorage

Then:

Navigate user to role homepage.

---

## Logout

Remove:

- Access Token
- Username
- Role
- Expiration

Redirect:

/login

---

## Session Expiration

If response returns:

401

Then:

1. Clear Session
2. Redirect Login
3. Display Toast

Message:

"Session expired. Please sign in again."

---

# Interceptors

## JWT Interceptor

Attach:

Authorization: Bearer <token>

Only for:

/svc/user
/svc/claim
/svc/assessment

Must not attach token to:

- Login
- Registration

---

## Error Interceptor

Convert backend error payload:

{
timestamp,
status,
error,
code,
message,
path
}

Into:

Material Snackbar Notification

---

# Authorization Model

## Roles

ROLE_CLAIMANT

ROLE_CLAIMS_OFFICER

ROLE_MANAGER

ROLE_ADMIN

---

# Route Security

Protected Routes:

/claimant/**
/officer/**
/manager/**
/admin/**
/profile

Unauthenticated:

Redirect:

/login?returnUrl=<path>

---

# Role Guard Rules

Claimant:

Can access only claimant routes.

Claims Officer:

Can access only officer routes.

Manager:

Can access only manager routes.

Admin:

Can access only admin routes.

Attempting another role route:

Redirect to own dashboard.

Show notification.

---

# UI Requirements

## Global Layout

Top Toolbar

Contains:

- Logo
- Notifications
- Profile Menu
- Logout

---

## Left Navigation

Role driven.

Menu generated from role.

Never display inaccessible pages.

---

# Claimant Module

Routes:

/claimant/dashboard
/claimant/claims/new
/claimant/claims
/claimant/claims/:id
/claimant/claims/:id/edit
/claimant/information-requests

Features:

- Dashboard
- Create Claim
- Edit Claim
- Claim Tracking
- Information Requests
- Profile

---

# Claims Officer Module

Routes:

/officer/dashboard
/officer/queue
/officer/assigned
/officer/review/:id
/officer/settlement/:id
/officer/history

Features:

- Queue
- Assigned Claims
- Review Workspace
- Settlement
- Assessment History

---

# Review Workspace Requirements

Must display:

Claim Summary

History Tabs

Information Requests Table

Actions:

Approve

Reject

Request Information

Approve and Reject:

Inline Forms

Request Information:

Material Dialog

All actions displayed side-by-side.

---

# Manager Module

Routes:

/manager/dashboard
/manager/claims
/manager/assignments
/manager/officer-metrics
/manager/liability-reports

Features:

- Overview
- Claims Listing
- Assign Claims
- Officer Performance
- Liability Reporting

---

# Currency Handling

Critical Rule:

Never combine different currencies.

Correct:

USD -> Total
SGD -> Total
AUD -> Total

Wrong:

USD + SGD + AUD

Manager reports must remain currency isolated.

---

# Admin Module

Routes:

/admin/dashboard
/admin/users
/admin/users/create
/admin/users/:id
/admin/users/:id/edit

Features:

- User Management
- Role Change
- Activation
- Deactivation

## Role Policy

Self-registered Claimants must remain Claimants. Claims Officer, Manager and Admin accounts are provisioned separately.

- A Claimant is never promoted to Claims Officer, Manager or Admin through the Admin UI.
- A staff account is never turned into a Claimant.
- Moves between the three staff roles are allowed. An admin never changes their own role.
- Create User offers staff roles only. Claimants register themselves.
- The rule lives in one place, `src/app/utils/role-policy.ts`. Screens must read it; never hardcode role lists in an admin component.
- The User Service does not return whether a Claimant self-registered, so every Claimant is treated as fixed.
- This is a UI rule. The backend still accepts any role, so never describe it as enforced. See `docs/ROLE_POLICY.md`.

---

# Notifications

Current Implementation:

Client Side Only

Toast Notifications

Header Notification Bell

Future Kafka integration should connect only through:

NotificationService

Avoid direct notification implementations in components.

---

# Forms

Always use:

Reactive Forms

Never use:

Template Driven Forms

---

# Validation

Client-side validation must mirror backend validation.

Examples:

Required Fields

Minimum Length

Maximum Length

Amount > 0

Valid Email

Password Requirements

Display validation immediately.

---

# Loading States

Required for all API operations.

Use:

- Skeletons
- Spinner
- Empty States

Never leave blank screens.

---

# Tables

All list screens must provide:

- Pagination
- Search
- Filters

Where supported by backend.

For unsupported backend filtering:

Use client-side filtering.

---

# Accessibility

Required:

- Keyboard Navigation
- Color Contrast
- Screen Reader Labels
- Focus States

Must pass Lighthouse Accessibility checks.

---

# Responsive Design

Supported Resolutions:

Mobile:
320px+

Tablet:
768px+

Desktop:
1024px+

Large Desktop:
1440px+

Side navigation collapses automatically on tablet/mobile.

---

# Testing

Framework:

Vitest

Minimum Coverage:

80%

Required Tests:

- Services
- Guards
- Interceptors
- Utility Functions
- Important Components

---

# E2E Coverage

Validate:

Authentication

Claim Submission

Claim Review

Approval

Rejection

Settlement

User Management

Role Routing

---

# Performance Requirements

Lazy Load all features.

Do not preload large dashboards.

Use:

trackBy

Signals

OnPush/Zoneless patterns

Avoid unnecessary subscriptions.

---

# Security Rules

Never:

- Store password
- Log JWT token
- Log personal data
- Expose backend stack traces

Always:

- Escape output
- Use Angular sanitizer when needed
- Route guard protected areas

---

# Known Backend Constraints

1. Approval and settlement are separate actions.

2. Information requests are text only.

3. Claim visibility is backend enforced.

4. Officers are derived from JWT identity.

5. Managers cannot fetch officer lists directly.

6. User role changes affect next login only.

7. JWT revocation does not exist.

8. The backend does not enforce the Role Policy: it accepts any role change except an admin's own.

---

# Development Standards

## Components

Maximum:

500 lines

If larger:

Split into child components.

---

## Services

Single responsibility only.

No UI logic.

---

## Smart Components

Responsible for:

- API calls
- State management

---

## Presentational Components

Responsible for:

- Rendering
- Emitting events

No API calls.

---

# Pull Request Checklist

- Builds successfully
- No console errors
- Tests pass
- Responsive verified
- Accessibility verified
- No hardcoded URLs
- No hardcoded roles
- No backend contract changes
- No unused dependencies
- No JWT logging

---

# Success Definition

A successful implementation:

- Supports all four roles
- Uses existing backend contracts unchanged
- Enforces role based routes
- Properly handles JWT authentication
- Is fully responsive
- Is accessible
- Has test coverage
- Can be deployed as static Angular assets behind a reverse proxy
