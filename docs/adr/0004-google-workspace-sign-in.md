# ADR-0004: Google Workspace sign-in, roles held in the application

- Status: Proposed
- Date: 2026-11-05

## Context

All users are CodeWalnut staff with Google Workspace accounts. NFR-BD-01 asks
for single sign-on and multi-factor authentication. Roles (Leadership, BD
lead, Account executive, Delivery and resourcing, Solutions and pre-sales,
Admin) drive what each user sees, including restricted rates and personal data
(FR-EXI-08).

## Decision

1. Sign-in through Google Workspace with OpenID Connect, using Spring
   Security's OAuth2 login. Only accounts in CodeWalnut's Workspace domain are
   accepted (checked on the `hd` claim and the email domain).
2. Multi-factor authentication is enforced by the Workspace policy, not by the
   application.
3. Roles are stored in the application database and assigned by an Admin. A
   user who signs in without a role sees nothing until one is assigned.
4. Sessions are server-side, with secure, HTTP-only cookies.

## Alternatives considered

- **Keycloak** (as on nPOS): powerful, but a second system to run and secure for
  10 users.
- **Roles from Google groups**: fewer screens, but ties role changes to Workspace
  administrators.

## Consequences

- Leaving CodeWalnut's Workspace removes access immediately.
- Admin screens for role assignment are needed in release 1.
