# BigBright ERP Codebase Dictionary

This directory contains comprehensive documentation for the BigBright ERP codebase, designed to serve as a decision-support system for both humans and AI agents.

## How to Use This Dictionary

This dictionary helps you understand the BigBright ERP codebase structure and make informed decisions about where to add new code, how to use existing code, and what patterns to follow.

### Quick Navigation
- **New to the codebase?** Start with [AI Context](AI_CONTEXT.md) for a token-efficient overview
- **Adding a new feature?** Check [Extension Points](WHERE_SHOULD_NEW_CODE_GO.md) for safe areas
- **Looking for a specific class?** Use the [Master Index](MASTER_INDEX.md) to find it
- **Understanding module structure?** Browse the [Module Documentation](modules/)

### Documentation Structure
```
docs/codebase-dictionary/
├── README.md (this file)
├── MASTER_INDEX.md - Complete class listing with status
├── AI_CONTEXT.md - AI agent quick reference guide
├── WHERE_SHOULD_NEW_CODE_GO.md - Extension point guidance
├── core-infrastructure/
│   ├── UTILITIES.md - Utility classes
│   ├── CONFIGS.md - Configuration classes
│   ├── SECURITY.md - Security components
│   ├── EXCEPTION_HANDLING.md - Exception handling
│   ├── AUDIT_FRAMEWORK.md - Audit services
│   ├── IDEMPOTENCY_FRAMEWORK.md - Idempotency support
│   └── ORCHESTRATOR.md - Event orchestration
└── modules/
    ├── accounting/
    ├── sales/
    ├── inventory/
    ├── factory/
    ├── purchasing/
    ├── auth/
    ├── hr/
    ├── admin/
    ├── company/
    ├── production/
    ├── reports/
    ├── invoice/
    ├── portal/
    └── rbac/
```

## Module Overview
| Module | Classes | Primary Purpose |
|--------|--------|---------------|
| **Accounting** | ~145 | Journal entries, AR/AP, reconciliation, financial reports |
| **Sales** | ~74 | Sales orders, dealers, dispatch, credit management |
| **Inventory** | ~71 | Finished goods, raw materials, batches, dispatch |
| **Factory** | ~69 | Production planning, packing, M2S workflow |
| **Purchasing** | ~48 | Purchase orders, goods receipts, invoices, suppliers |
| **Auth** | ~47 | Authentication, MFA, password management, sessions |
| **HR** | ~47 | Employees, attendance, leave, payroll |
| **Admin** | ~39 | User management, support tickets, exports |
| **Company** | ~37 | Multi-tenancy, onboarding, lifecycle management |
| **Production** | ~33 | Product catalog, brands, SKUs |
| **Reports** | ~28 | Financial reports, dashboards, analytics |
| **Invoice** | ~14 | Invoice generation, PDF, settlement |
| **Portal** | ~10 | Dashboard insights, tenant enforcement |
| **RBAC** | ~11 | Roles, permissions, authorization |

**Total: ~858 classes**

## Canonicality Status
All components are marked with one of the following statuses:
- **Canonical**: Primary implementation, use this
- **Scoped**: Valid only in specific workflow/area
- **Legacy**: Still used, avoid new usage
- **Duplicate-risk**: Overlaps with other code
- **Deprecated**: Phase out

## Contributing Guidelines
When adding new components:
1. Follow existing package structure and naming conventions
2. Add entries to the dictionary following the schema
3. Extract exact method signatures from source code
4. Identify callers through usage search
5. Determine canonicality status
6. Document any invariants protected
