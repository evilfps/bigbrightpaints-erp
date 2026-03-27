# Documentation Confidence Levels

All entries in this Codebase Dictionary are marked with a confidence level to indicate the reliability of the information.

## Confidence Levels

| Level | Badge | Meaning |
|-------|-------|---------|
| **EXACT** | `![EXACT]` | Extracted directly from source code - method signatures, dependencies, callers verified |
| **INFERRED** | `![INFERRED]` | Reasonable inference from code patterns - may need verification |
| **PARTIAL** | `![PARTIAL]` | Incomplete analysis - some aspects documented, others pending |
| **STALE-RISK** | `![STALE-RISK]` | May be outdated - last verified more than 30 days ago |

## Entry Schema (Machine-Readable)

Every documented component follows this schema:

```yaml
name: string                    # Class/service name
module: string                  # Module name (accounting, sales, etc.)
file_path: string               # Exact path to source file
type: enum                      # Controller | Service | Repository | Entity | DTO | Helper | Config | Event
canonicality: enum              # Canonical | Scoped | Legacy | Duplicate-risk | Deprecated | Dangerous
confidence: enum                # EXACT | INFERRED | PARTIAL | STALE-RISK
last_verified: date             # YYYY-MM-DD format
verified_commit: string         # Git commit SHA

# Relationships
callers: string[]               # Classes that call this
dependencies: string[]          # Injected beans / imports
side_effects: string[]          # DB writes, events, external calls

# Sensitivity Flags
tenant_sensitive: boolean       # Uses CompanyContext
accounting_sensitive: boolean   # Affects financial records
idempotent: boolean             # Safe to retry
requires_auth: boolean          # Needs authenticated user

# Workflow
owned_workflows: string[]       # Business flows this owns
invariants_protected: string[]  # Business rules enforced

# Extension
safe_to_extend: boolean         # OK to add methods
extension_points: string[]      # Where to add new code
prohibited_patterns: string[]   # What NOT to do
```

## Confidence Level Guidelines

### EXACT
Use when:
- Method signatures extracted from source code
- Dependencies verified via @Autowired/@RequiredArgsConstructor
- Callers found through ripgrep search
- Last verified within 30 days

### INFERRED
Use when:
- Dependencies inferred from package imports
- Callers estimated from code patterns
- Purpose derived from class name and context

### PARTIAL
Use when:
- Only public methods documented (private methods not analyzed)
- Some callers identified, but search incomplete
- Side effects partially documented

### STALE-RISK
Use when:
- Not verified in 30+ days
- Source code may have changed since documentation
- Mark for re-verification during next sprint

## Verification Process

To verify a documented entry:

1. **Check source file exists**
   ```bash
   test -f erp-domain/src/main/java/com/bigbrightpaints/erp/...
   ```

2. **Verify method signatures**
   ```bash
   rg "public.*methodName" erp-domain/src/main/java/...
   ```

3. **Find callers**
   ```bash
   rg "ClassName" erp-domain/src/main/java/ --type java
   ```

4. **Update confidence level and last_verified date**

## Contributing

When adding new documentation:
1. Always include confidence level
2. Include file_path for verification
3. Note the git commit SHA when verified
4. Mark as STALE-RISK if uncertain
