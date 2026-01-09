# Lines of Code Report

**Branch:** `copilot/get-code-lines-in-branch`  
**Date:** 2026-01-09 14:51:13 UTC  
**Repository:** bigbrightpaints-erp

---

## Summary

**Total Lines of Code: 126,138**  
**Total Code Files: 981**

---

## Breakdown by Language

| Language          | Files | Lines of Code |
|-------------------|------:|-------------:|
| Java              | 558   | 55,836       |
| TypeScript        | 286   | 38,751       |
| YAML              | 13    | 21,694       |
| SQL               | 91    | 4,200        |
| JavaScript        | 24    | 3,885        |
| Shell             | 7     | 1,114        |
| Python            | 2     | 658          |
| **TOTAL**         | **981** | **126,138** |

---

## Methodology

Lines of code were counted using the following methodology:

1. **Included File Types:** `.java`, `.ts`, `.tsx`, `.js`, `.jsx`, `.py`, `.sh`, `.sql`, `.yaml`, `.yml`
2. **Excluded Directories:** 
   - `node_modules/` (dependencies)
   - `.git/` (version control)
   - `target/`, `build/`, `dist/` (build artifacts)
   - `.history/` (editor history)
   - `vendor/` (third-party code)

3. **Counting Method:** Standard `wc -l` command to count all lines in each file, including blank lines and comments.

---

## Key Findings

- **Primary Language:** Java (44.3% of total lines)
- **Secondary Language:** TypeScript (30.7% of total lines)
- **Configuration:** YAML files contribute 17.2% of lines
- **Database Scripts:** 91 SQL files with 4,200 lines
- **Build Scripts:** 7 shell scripts for automation

---

## Repository Structure

The codebase is organized into several main components:

- `erp-domain/` - Core ERP domain logic (Java backend)
- `clients/` - TypeScript client applications
- `cypress/`, `cypress-e2e-tests/` - End-to-end testing
- `tally-ingestion-backend/` - Tally integration backend
- `scripts/` - Build and deployment scripts
- `cloud/` - Cloud deployment configurations

---

## Answer

**This branch has 126,138 lines of code across 981 files.**
