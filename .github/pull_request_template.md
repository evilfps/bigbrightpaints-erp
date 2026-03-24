# Pull Request

## Description
<!-- Provide a clear and concise description of the changes in this PR -->

## Type of Change
<!-- Mark the relevant option with an 'x' -->
- [ ] 🐛 Bug fix (non-breaking change which fixes an issue)
- [ ] ✨ New feature (non-breaking change which adds functionality)
- [ ] 💥 Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] 📚 Documentation update
- [ ] 🔧 Configuration change
- [ ] ♻️ Refactoring (no functional changes)
- [ ] ✅ Test addition/update

## Module(s) Affected
<!-- Mark all affected modules with an 'x' -->
- [ ] Accounting
- [ ] Inventory
- [ ] Sales
- [ ] Purchasing
- [ ] HR/Payroll
- [ ] Manufacturing
- [ ] Portal
- [ ] Reports
- [ ] Admin
- [ ] Auth/Security
- [ ] Orchestrator
- [ ] Core/Infrastructure
- [ ] Other: [specify]

## Related Issues
<!-- Link to related issues using #issue-number format -->
Closes #
Related to #

## Changes Made
<!-- List the key changes made in this PR -->
1. 
2. 
3. 

## Testing
<!-- Describe the testing performed to validate these changes -->

### Test Cases
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] Manual testing completed

### Test Results
<!-- Include relevant test output or screenshots -->
```
Paste test output here
```

## Database Migrations
<!-- Check if this PR includes database migrations -->
- [ ] No database changes
- [ ] Migration added to `db/migration_v2/`
- [ ] Migration tested locally

**Migration file(s):**
<!-- If applicable, list the migration files -->

## Breaking Changes
<!-- Describe any breaking changes and how to migrate -->
**No breaking changes** / **Breaking changes:**

## Checklist
<!-- Mark completed items with an 'x' -->
- [ ] Code compiles without errors (`mvn compile`)
- [ ] Code follows project style guidelines (`mvn spotless:check`)
- [ ] All tests pass locally (`mvn test -Pgate-fast`)
- [ ] Coverage gates pass (Tier A ≥ 38%, Bundle ≥ 55%)
- [ ] No new Checkstyle warnings introduced
- [ ] Documentation updated (if applicable)
- [ ] CHANGELOG updated (if user-facing change)
- [ ] High-risk changes documented in `docs/approvals/R2-CHECKPOINT.md` (if applicable)

## High-Risk Changes (If Applicable)
<!-- Check if this PR touches high-risk areas -->
- [ ] This PR touches high-risk paths (auth, company, RBAC, HR, accounting, orchestrator, or `db/migration_v2`)
- [ ] R2 checkpoint evidence added to `docs/approvals/R2-CHECKPOINT.md`
- [ ] Enterprise policy check passed (`bash ci/check-enterprise-policy.sh`)

## Screenshots (If Applicable)
<!-- Add screenshots for UI changes -->

## Additional Context
<!-- Add any other context about the PR here -->

## Reviewer Notes
<!-- Optional: specific areas you'd like reviewers to focus on -->

---

<!-- For AI agents: Remember to follow AGENTS.md guidelines -->
<!-- Use Factory-droid as integration base for review -->
<!-- Ensure high-risk changes have R2 checkpoint documentation -->
