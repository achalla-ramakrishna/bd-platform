# ADR-0007: Host on Railway for now

- Status: Accepted
- Date: 2026-11-05

## Context

The application is one container plus a MySQL database (ADR-0003), used by
about 10 people. CodeWalnut wants it running quickly and cheaply, without
setting up a cloud account, networking and a managed database on AWS, Azure or
Google Cloud.

NFR-BD-04 asked for data hosted in an India region. Railway does not offer an
India region (to be checked at setup); its nearest region is Southeast Asia
(Singapore).

## Decision

1. Host on Railway for now, as leadership decided on 5 November 2026.
2. One Railway project with two environments, `staging` and `production`. Each
   has:
   - an application service built from the repository's Dockerfile, deployed
     from GitHub (`main` to staging; production promoted by hand);
   - a Railway MySQL service, with daily backups (NFR-BD-08).
3. Use the region closest to India that Railway offers (Southeast Asia,
   Singapore) for both services.
4. Secrets (Google OAuth client, database credentials) are Railway environment
   variables, never committed.
5. Nothing in the code depends on Railway: configuration comes from
   environment variables, and the database is plain MySQL 8.4.

## Alternatives considered

- **AWS, Azure or Google Cloud in Mumbai or Hyderabad**: meets NFR-BD-04, but
  more setup and running effort than this stage needs.
- **A single virtual machine**: cheap, but backups, TLS and deployments become
  CodeWalnut's job.

## Consequences

- NFR-BD-04 (data in India) is not met while on Railway. India's DPDP Act
  allows personal data to be processed outside India unless the government
  restricts the destination country, so this is acceptable for now. Revisit
  before loading large volumes of prospect personal data, or if a client
  contract requires data to stay in India.
- Moving to another cloud later means rebuilding the two services and
  restoring a MySQL backup; no code changes.
- Check Railway's MySQL backup and restore options at setup, and test a
  restore before go-live (NFR-BD-08).
