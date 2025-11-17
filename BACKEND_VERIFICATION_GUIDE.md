# 🔍 **COMPLETE BACKEND VERIFICATION GUIDE**

Multiple ways to thoroughly verify that your BigBright ERP backend is fully functional across all modules and capabilities.

---

## **🚀 QUICK HEALTH CHECKS (30 seconds)**

### **1. Basic Connectivity**
```bash
# Test if backend is running
curl http://localhost:8080/actuator/health

# Expected response: {"status":"UP"}
```

### **2. Admin Login Test**
```bash
# Test authentication works
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!"}'

# Expected: {"success":true,"data":{"token":"..."}}
```

### **3. Database Connectivity**
```bash
# Test database via backend
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain -c "SELECT COUNT(*) FROM companies;"

# Expected: Should return count of companies
```

---

## **🔧 COMPREHENSIVE AUTOMATED CHECKS**

### **Method 1: Node.js Health Check Script (Recommended)**

**Most comprehensive single-script check:**
```bash
# Navigate to test directory
cd cypress-e2e-tests

# Run comprehensive backend check
node backend-health-check.js

# With custom settings
BACKEND_URL=http://localhost:8081 ADMIN_EMAIL=admin@company.com node backend-health-check.js
```

**What it checks:**
- ✅ **Infrastructure**: Health, database, API documentation
- ✅ **Authentication**: Login, token validation, company switching  
- ✅ **All Modules**: Sales, accounting, inventory, factory, HR, invoices
- ✅ **Workflows**: Orchestrator, dashboards, business processes
- ✅ **Business Logic**: CRUD operations, data validation
- ✅ **Performance**: Response times, resource usage

**Output:** Detailed pass/fail report with recommendations

---

### **Method 2: Database-Specific Check**

**Deep database verification:**
```bash
cd cypress-e2e-tests
node database-check.js

# With custom database settings
DB_HOST=localhost DB_NAME=erp_domain DB_USER=erp node database-check.js
```

**What it verifies:**
- ✅ **Connection**: Database connectivity and responsiveness
- ✅ **Structure**: All required tables, constraints, indexes
- ✅ **Data Integrity**: Foreign key consistency, balanced journal entries
- ✅ **Migrations**: Flyway migration status and history
- ✅ **Performance**: Table sizes, connection statistics
- ✅ **Business Data**: Records in key tables (dealers, orders, accounts)

---

### **Method 3: Shell Script Check (Linux/Mac)**

**Comprehensive bash-based testing:**
```bash
# Make executable
chmod +x check-backend.sh

# Run full check
./check-backend.sh

# With custom backend URL
BACKEND_URL=http://localhost:8081 ./check-backend.sh
```

**Features:**
- 🔍 Tests all API endpoints systematically
- ⏱️ Measures response times
- 📊 Provides health score and recommendations
- 🎯 Tests actual business operations (create dealer, order, etc.)

---

## **🧪 CYPRESS E2E TEST SUITE**

### **Complete API and UI Testing**

**Run setup verification:**
```bash
cd cypress-e2e-tests
npm run test:setup
```

**Run specific test categories:**
```bash
npm run test:api         # Backend API tests
npm run test:workflows   # End-to-end business workflows  
npm run test:smoke      # Quick smoke tests
npm run cypress:run     # All tests headless
npm run cypress:open    # Interactive test runner
```

**What it tests:**
- 🔐 **Authentication**: Login, MFA, token management
- 💼 **Business Workflows**: Complete order-to-cash, inventory management
- 🧾 **Accounting**: Journal entries, ledger updates, balance validation
- 🏭 **Production**: Manufacturing workflows, inventory reservations
- 👥 **HR**: Employee management, payroll processing
- 🔄 **Integration**: Cross-module data consistency

---

## **📋 MANUAL VERIFICATION CHECKLIST**

### **Core Infrastructure**
- [ ] Backend responds to health check
- [ ] Swagger UI accessible at `/swagger-ui/`
- [ ] Database connection established
- [ ] Admin user can login
- [ ] Company context switching works

### **Authentication & Security**  
- [ ] JWT tokens generate correctly
- [ ] Token validation works
- [ ] MFA setup/disable functions
- [ ] Role-based permissions enforced
- [ ] Password change functionality

### **Sales Module**
- [ ] List dealers: `GET /api/v1/sales/dealers`
- [ ] Create dealer: `POST /api/v1/sales/dealers`
- [ ] List orders: `GET /api/v1/sales/orders`
- [ ] Create order: `POST /api/v1/sales/orders`
- [ ] Order confirmation workflow
- [ ] GST calculations (NONE, LINE_ITEM, ORDER_TOTAL modes)

### **Accounting Module**
- [ ] Chart of accounts: `GET /api/v1/accounting/accounts`
- [ ] Journal entries: `GET /api/v1/accounting/journal-entries`
- [ ] Create journal entry with balanced debits/credits
- [ ] Dealer receipt recording
- [ ] Payroll payment journals

### **Inventory Module**
- [ ] Finished goods: `GET /api/v1/inventory/finished-goods`
- [ ] Raw materials: `GET /api/v1/inventory/raw-materials`
- [ ] Inventory batches and FIFO costing
- [ ] Stock reservations for orders
- [ ] Packaging slips generation

### **Factory Module**
- [ ] Production plans: `GET /api/v1/factory/plans`
- [ ] Production batches: `GET /api/v1/factory/batches`
- [ ] Production logs with material consumption
- [ ] Factory tasks management

### **HR Module**
- [ ] Employee listing: `GET /api/v1/hr/employees`
- [ ] Leave requests workflow
- [ ] Payroll run generation
- [ ] Payroll accounting integration

### **Orchestration Workflows**
- [ ] Order approval: `POST /api/v1/orchestrator/orders/{id}/approve`
- [ ] Inventory reservation and production scheduling
- [ ] Dispatch workflow: `POST /api/v1/orchestrator/dispatch`
- [ ] Payroll workflow: `POST /api/v1/orchestrator/payroll`

---

## **📊 TESTING MATRIX BY SCENARIO**

### **Scenario 1: New Installation Verification**
```bash
# Quick verification (5 minutes)
curl http://localhost:8080/actuator/health
node backend-health-check.js
npm run test:smoke
```

### **Scenario 2: Pre-Production Readiness**  
```bash
# Comprehensive check (15 minutes)
./check-backend.sh
node database-check.js
npm run test:all
```

### **Scenario 3: Post-Deployment Validation**
```bash
# Production health check (2 minutes)  
BACKEND_URL=https://your-production-url.com node backend-health-check.js
npm run test:api
```

### **Scenario 4: Troubleshooting Issues**
```bash
# Detailed diagnostics (10 minutes)
node database-check.js                    # Check database issues
npm run test:setup                       # Verify basic functionality
npm run cypress:open                     # Interactive debugging
```

---

## **🎯 SUCCESS CRITERIA**

### **✅ Fully Functional Backend Should Have:**

**Infrastructure:**
- Health check returns `{"status":"UP"}`
- OpenAPI docs accessible at `/v3/api-docs`
- Database connection established
- All required tables exist with proper constraints

**Authentication:**
- Admin login succeeds and returns JWT token
- Token validation works for protected endpoints
- Company switching updates token context
- MFA setup/disable functions properly

**Business Modules:**
- All CRUD operations work (Create, Read, Update, Delete)
- Cross-module data consistency maintained
- Accounting journals automatically balance
- Inventory movements tracked with audit trail

**Workflows:**
- Order approval triggers inventory reservation
- Production shortages create urgent plans
- Shipment posts sales journal and invoice
- Payroll generates accounting entries

**Performance:**
- API responses under 2 seconds
- Database queries optimized with indexes
- No memory leaks or connection issues
- Concurrent user support

---

## **🚨 COMMON ISSUES & DIAGNOSTICS**

### **Backend Won't Start**
```bash
# Check if port is available
netstat -tulpn | grep :8080

# Check application logs
tail -f erp-domain/logs/erp-backend.log

# Test with different port
SERVER_PORT=8081 java -jar erp-domain/target/erp-domain-0.1.0-SNAPSHOT.jar
```

### **Database Connection Issues**
```bash
# Test PostgreSQL is running
pg_ctl status

# Test connection manually  
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain

# Check database logs
tail -f /var/log/postgresql/postgresql-*.log
```

### **Authentication Problems**
```bash
# Check admin user exists
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain \
  -c "SELECT email, enabled FROM app_users WHERE email = 'admin@bbp.dev';"

# Test login endpoint directly
curl -v -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!"}'
```

### **Module-Specific Issues**
```bash
# Check specific module endpoints
curl -H "Authorization: Bearer YOUR_TOKEN" \
  http://localhost:8080/api/v1/sales/dealers

# Verify database tables for module
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain \
  -c "SELECT COUNT(*) FROM dealers;"
```

---

## **⏱️ TIME ESTIMATES**

| Verification Method | Time Required | Coverage Level |
|-------------------|---------------|----------------|
| Quick health check | 30 seconds | Basic connectivity |
| Backend health script | 2-5 minutes | Comprehensive API testing |
| Database check | 1-2 minutes | Database integrity |
| Shell script check | 5-10 minutes | Full system validation |
| Cypress smoke tests | 3-5 minutes | Critical paths |
| Full E2E test suite | 10-20 minutes | Complete functionality |

---

## **📈 MONITORING & ALERTS**

### **Production Monitoring Setup**
```bash
# Health check endpoint for monitoring tools
curl http://your-backend.com/actuator/health

# Metrics endpoint (if enabled)
curl http://your-backend.com/actuator/metrics

# Custom health check script for cron
*/5 * * * * /path/to/check-backend.sh > /var/log/erp-health.log 2>&1
```

### **Key Metrics to Track**
- Response times for critical endpoints
- Database connection pool utilization  
- Failed authentication attempts
- Unbalanced journal entries
- Order processing errors
- System resource usage (CPU, memory, disk)

---

## **🎉 CONCLUSION**

**For quick verification:** Use `node backend-health-check.js`  
**For deep database analysis:** Use `node database-check.js`  
**For complete testing:** Use the full Cypress test suite  
**For production monitoring:** Use the shell script in automated checks

Each method provides different levels of detail, allowing you to choose the appropriate verification approach based on your needs and available time.

**A fully verified backend should pass all infrastructure, authentication, module, workflow, and business logic tests with zero failures.** 🚀
