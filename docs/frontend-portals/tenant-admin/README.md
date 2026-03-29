# Tenant Admin Portal

## Purpose

This portal owns tenant-scoped administration for the current company. It is the shell for session bootstrap, user lifecycle, export approvals, support tickets, and the tenant-visible changelog.

## Users

- Primary role: `ROLE_ADMIN`
- Treat the tenant-admin shell, navigation, and route tree as `ROLE_ADMIN` only.
- Backend reads may also allow `ROLE_ACCOUNTING` for some shared data, but that
  does not create accounting access to the tenant-admin shell.

## What belongs here

- Shell bootstrap from `GET /api/v1/auth/me`
- User list, create, edit, enable or disable, suspend or unsuspend, MFA disable, force reset password, delete
- Approval inbox for tenant-admin decisions
- Export approval decisions
- Support ticket list, detail, create
- Tenant-visible changelog read screens

## What does not belong here

- Superadmin onboarding and tenant lifecycle control
- Accounting journal entry, reversal, period-close, reconciliation, report execution
- Factory dispatch confirmation or production actions
- Dealer self-service

## Critical frontend rules

- The active tenant is always the `companyCode` from `GET /api/v1/auth/me`.
- Do not design or persist a company switcher.
- Do not expose `ROLE_SUPER_ADMIN` anywhere in role-pickers or shell navigation.
- Treat `mustChangePassword=true` as a blocking pre-shell state.
