# ADR-0002: Technology stack: React + Spring Boot + MySQL

- Status: Accepted
- Date: 2026-11-05

## Context

The application is an internal tool for about 10 users (NFR-BD-10). It must be
quick to build, cheap to run and easy for CodeWalnut engineers to maintain.

## Decision

- Back end: Java 25, Spring Boot 4, Spring Security, Spring Data JPA.
- Database: MySQL 8.4 with Flyway migrations.
- Front end: React 19 with TypeScript, built with Vite and served by the
  Spring Boot application.
- API: REST, OpenAPI-first, with a generated TypeScript client.

Leadership chose this stack on 5 November 2026. It matches the stack
CodeWalnut uses on other enterprise work, so engineers move between projects
easily.

## Alternatives considered

- **Next.js + PostgreSQL**: one TypeScript codebase, the least code for a small
  tool. Not chosen.
- **React + NestJS + PostgreSQL**: all TypeScript with a separate API. Not
  chosen.

## Consequences

- Two languages (Java and TypeScript) in one repository; the OpenAPI contract
  keeps them in step.
- Mature libraries for spreadsheet parsing (Apache POI), security and
  migrations.
