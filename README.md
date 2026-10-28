# Chubb APAC Claims Management System

## Overview

Chubb APAC Claims Management System is a microservices-based insurance claims platform that supports the complete claim lifecycle from claim submission through review, approval, settlement, reporting, and administration.

The solution is designed for four business roles:

- Claimant
- Claims Officer
- Manager
- Administrator

---

# Business Workflow

```text
Claimant
    |
    | Submit Claim
    v
SUBMITTED
    |
    v
Claims Officer Review
    |
    +------------------------+
    |                        |
    | Request Information    |
    v                        v
MORE_INFO_REQUIRED      REJECTED (Final)
    |
    | Claimant Response
    v
UNDER_REVIEW
    |
    | Approve
    v
APPROVED
    |
    | Settlement
    v
SETTLED (Final)
```

---

# Solution Architecture

```text
                    Angular Frontend
                           |
                           |
                      JWT Token
                           |
 ------------------------------------------------
 |                      |                       |
 |                      |                       |
User Service      Claim Service      Assessment Service
   8185               8181                 8182
     |                  |                    |
  user_db           claim_db          assessment_db
```

---

# Service Responsibilities

## User Service (Port 8185)

Responsible for:

- User Registration
- Authentication
- JWT Generation
- User Management
- Role Management
- Account Activation / Deactivation

### Main Roles

```text
CLAIMANT
CLAIMS_OFFICER
MANAGER
ADMIN
```

---

## Claim Service (Port 8181)

Responsible for:

- Claim Creation
- Claim Tracking
- Claim Assignment
- Claim Updates
- Claim History
- Management Reporting

### Key Features

- Create claim
- View claim status
- Edit submitted claims
- Assignment history
- Status history
- Officer workload reports
- Liability reports

---

## Assessment Service (Port 8182)

Responsible for:

- Claim Review
- Information Requests
- Approval
- Rejection
- Settlement
- Assessment History

### Key Features

- Start review
- Request information
- Approve claim
- Reject claim
- Settle claim
- Assessment audit history

---

# Security Architecture

## Authentication Flow

```text
User Login
      |
      v
User Service
      |
      v
Generate JWT
      |
      v
Frontend Stores Token
      |
      v
Authorization Header
      |
      v
Protected APIs
```

Example:

```http
Authorization: Bearer <JWT_TOKEN>
```

### Security Features

- JWT Authentication
- Role-Based Authorization
- Stateless Security
- Protected APIs
- Service-to-Service Authentication
- BCrypt Password Hashing

---

# Claim Lifecycle

```text
SUBMITTED
    |
    v
UNDER_REVIEW
    |
    +----------------+
    |                |
    v                v
MORE_INFO       REJECTED
REQUIRED         (Final)
    |
    v
UNDER_REVIEW
    |
    v
APPROVED
    |
    v
SETTLED
 (Final)
```

### Business Rules

- Claimants can edit only submitted claims.
- Officers review claims.
- Additional information can be requested.
- Approval and Settlement are separate processes.
- Rejected claims cannot be settled.
- Settled claims are final.

---

# Project Structure

```text
microservices/
│
├── claims-common
│
├── user-service
│
├── claim-service
│
├── assessment-service
│
├── system-tests
│
└── scripts
```

---

## Shared Module

### claims-common

Contains:

```text
JWT Security
Role Definitions
Exception Handling
Shared DTOs
Access Rules
Service Token Logic
Common Utilities
```

---

# Technology Stack

## Backend

- Java 17
- Spring Boot 3
- Spring Security
- Spring Data JPA
- Flyway
- JWT

## Database

- PostgreSQL
- H2 (Development)

## API Documentation

- OpenAPI 3
- Swagger UI

## Testing

- JUnit 5
- Mockito
- MockMvc
- Integration Tests
- System Tests

---

# Database Architecture

Each microservice owns its own database.

```text
User Service
└── user_db

Claim Service
└── claim_db

Assessment Service
└── assessment_db
```

Benefits:

- Loose Coupling
- Independent Deployment
- Independent Scaling
- Better Maintainability

---

# Major Features

## Claimant

- Register Account
- Login
- Submit Claim
- Edit Claim
- Track Claim
- Answer Information Requests
- View History

## Claims Officer

- Take Claim
- Review Claim
- Request Information
- Approve Claim
- Reject Claim
- Settle Claim
- View Assessment History

## Manager

- Assign Claims
- View All Claims
- Officer Metrics
- Liability Reports
- Workload Monitoring

## Administrator

- Create Users
- Manage Roles
- Activate Users
- Deactivate Users
- System Administration

---

# Testing

The solution includes:

```text
580 Automated Tests
```

Coverage includes:

- Unit Testing
- Integration Testing
- Security Testing
- Migration Testing
- System Testing
- Role Authorization Testing
- Claim Lifecycle Testing

---

# Running the Application

## Start Backend

```powershell
.\microservices\scripts\start-all.ps1
```

## Stop Backend

```powershell
.\microservices\scripts\stop-all.ps1
```

## Health Checks

```text
http://localhost:8185/actuator/health
http://localhost:8181/actuator/health
http://localhost:8182/actuator/health
```

Expected:

```json
{
  "status": "UP"
}
```

---

# Demo Accounts

## Password

```text
password123
```

## Users

```text
claimant1   -> CLAIMANT
officer1    -> CLAIMS_OFFICER
manager1    -> MANAGER
admin1      -> ADMIN
```

---

# Swagger Documentation

```text
User Service
http://localhost:8185/swagger-ui/index.html

Claim Service
http://localhost:8181/swagger-ui/index.html

Assessment Service
http://localhost:8182/swagger-ui/index.html
```

---

# Demo Scenario

1. Claimant submits a claim.
2. Claims Officer reviews the claim.
3. Officer requests additional information.
4. Claimant provides information.
5. Officer approves claim.
6. Officer settles claim.
7. Manager reviews workload and reports.
8. Administrator manages users.

---

# Future Enhancements

- API Gateway
- Kafka Event Streaming
- Notifications
- Refresh Tokens
- Document Uploads
- Audit Dashboard
- Distributed Tracing

---

# Documentation

```text
README.md              Project Overview
API_TEST_CASES.md      API Contracts
DESIGN_DECISIONS.md    Architecture Decisions
CLAUDE.md              Development Guide
```

---

# Conclusion

This solution demonstrates:

✅ Microservice Architecture  
✅ JWT Authentication  
✅ Role-Based Access Control  
✅ Complete Insurance Claim Lifecycle  
✅ Database Per Service Design  
✅ REST-Based Service Communication  
✅ Automated Testing  
✅ Scalable and Maintainable Architecture