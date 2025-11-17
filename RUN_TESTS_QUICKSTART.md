# Quick Start Guide - Running E2E Tests

## 🚀 TL;DR - Run Tests Now

```bash
# 1. START DOCKER DESKTOP FIRST! ← Most important step
# Verify Docker is running:
docker ps

# 2. Navigate to project
cd erp-domain

# 3. Run Smoke Tests (2 mins) - Deploy confidence
mvn test -Dtest="ApplicationSmokeTest,CriticalPathSmokeTest"

# 4. Run ALL tests (10-15 mins) - Full confidence
mvn test -Dtest="ApplicationSmokeTest,CriticalPathSmokeTest,CompleteProductionCycleTest,OrderFulfillmentE2ETest,JournalEntryE2ETest,EdgeCasesTest,BusinessLogicRegressionTest"
```

## ⚠️ Common Issues

### Error: "Could not find a valid Docker environment"
**Solution:** Start Docker Desktop and wait for it to fully start up.

```bash
# Verify Docker is running:
docker ps

# If Docker is running, you'll see a list of containers (may be empty)
# If not running, you'll get an error
```

### Tests Take Long First Time
**Expected:** First run downloads PostgreSQL Docker image (~100MB)
**Subsequent runs:** Much faster (uses cached image)

## 📊 Test Categories

### Smoke Tests (14 tests, ~2 mins)
```bash
mvn test -Dtest="*SmokeTest"
```
- Application startup
- Database connectivity
- Authentication
- Core workflows

### E2E Tests (28 tests, ~8 mins)
```bash
mvn test -Dtest="*E2ETest"
```
- Production cycles
- Sales workflows
- Accounting flows
- Edge cases

### Regression Tests (8 tests, ~2 mins)
```bash
mvn test -Dtest="*RegressionTest"
```
- Business logic validation
- Data integrity checks

## ✅ Expected Output

When tests run successfully, you'll see:

```
[INFO] Running com.bigbrightpaints.erp.smoke.ApplicationSmokeTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.bigbrightpaints.erp.smoke.CriticalPathSmokeTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
...
[INFO] BUILD SUCCESS
```

## 📝 Test Reports

After running tests, check:
- **Console output:** Real-time results
- **Report files:** `erp-domain/target/surefire-reports/`
- **Summary:** [TEST_SUITE_SUMMARY.md](TEST_SUITE_SUMMARY.md)

## 🎯 What Each Test Suite Validates

| Suite | Tests | Validates |
|-------|-------|-----------|
| **ApplicationSmokeTest** | 5 | App starts, DB works, login works |
| **CriticalPathSmokeTest** | 9 | Core business workflows |
| **CompleteProductionCycleTest** | 7 | Manufacturing processes |
| **OrderFulfillmentE2ETest** | 7 | Sales and order flows |
| **JournalEntryE2ETest** | 7 | Accounting and financial |
| **EdgeCasesTest** | 7 | Error handling & validation |
| **BusinessLogicRegressionTest** | 8 | Business rules never break |
| **TOTAL** | **50** | **All critical flows** |

## 🐛 Troubleshooting

### Still having issues?
1. Ensure Java 21 is installed: `java -version`
2. Ensure Maven 3.9+ is installed: `mvn -version`
3. Ensure Docker is running: `docker ps`
4. Check the detailed summary: [TEST_SUITE_SUMMARY.md](TEST_SUITE_SUMMARY.md)

### Want to see verbose output?
```bash
mvn test -Dtest="*SmokeTest" -X
```

## 🎉 Success Criteria

Tests are passing when you see:
```
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

This means your ERP system is ready for production! 🚀
