# 🚀 **QUICK START GUIDE**

Get your Cypress E2E testing suite up and running in **under 5 minutes**!

## **⚡ Super Quick Setup (Automated)**

### **Option 1: Linux/Mac**
```bash
cd cypress-e2e-tests
chmod +x quick-start.sh
./quick-start.sh
```

### **Option 2: Windows**
```bash
cd cypress-e2e-tests
quick-start.bat
```

### **Option 3: Manual (All Platforms)**
```bash
cd cypress-e2e-tests

# 1. Check prerequisites
node setup-check.js

# 2. Install dependencies
npm install

# 3. Update configuration (see below)
# Edit cypress.config.js

# 4. Run initial setup test
npm run test:setup

# 5. Open test runner
npm run cypress:open
```

---

## **🎯 Prerequisites Checklist** 

Before running the scripts, ensure:

- [ ] **Node.js 16+** installed (`node --version`)
- [ ] **Backend running** at http://localhost:8080 (or 8081)
- [ ] **Database accessible** (PostgreSQL with admin user)
- [ ] **Admin credentials** known (default: admin@bbp.dev / ChangeMe123!)

**Optional for UI tests:**
- [ ] **Frontend running** at http://localhost:3002 (or 3000)

---

## **⚙️ Manual Configuration**

If you prefer to configure manually, update `cypress.config.js`:

```javascript
module.exports = defineConfig({
  e2e: {
    baseUrl: 'http://localhost:3002',    // 👈 Your React app URL
    env: {
      apiUrl: 'http://localhost:8080',   // 👈 Your backend URL
      adminEmail: 'admin@bbp.dev',       // 👈 Admin email
      adminPassword: 'ChangeMe123!',     // 👈 Admin password
      companyCode: 'BBP'                 // 👈 Company code
    }
  }
})
```

---

## **🧪 First Test Run**

After setup, run these commands to verify everything works:

```bash
# 1. Smoke test (quick verification)
npm run test:smoke

# 2. API tests (backend only)
npm run test:api

# 3. UI tests (requires frontend)
npm run test:ui

# 4. Full workflow tests
npm run test:workflows

# 5. Interactive mode (recommended)
npm run cypress:open
```

---

## **🎮 Available Commands**

### **Basic Test Execution**
```bash
npm run cypress:open        # Interactive test runner
npm run cypress:run         # Headless all tests
npm run test:all           # All tests (alias)
```

### **Test Categories**
```bash
npm run test:setup         # Setup verification
npm run test:api           # Backend API tests
npm run test:ui            # Frontend UI tests  
npm run test:workflows     # End-to-end workflows
npm run test:smoke         # Quick verification
```

### **Specific Tests**
```bash
npm run test:auth          # Authentication only
npm run test:sales         # Sales management
npm run test:dealers       # Dealer management UI
npm run test:order-flow    # Complete order workflow
```

### **Utilities**
```bash
npm run setup:check        # Check prerequisites
npm run db:seed           # Create test data
npm run db:cleanup        # Clean test data
```

---

## **🔧 Common Issues & Quick Fixes**

### **❌ "Backend not accessible"**
```bash
# Check if backend is running
curl http://localhost:8080/actuator/health

# Start backend if needed
cd ../erp-domain
./mvnw spring-boot:run
```

### **❌ "Frontend not accessible"** 
```bash
# Check if frontend is running  
curl http://localhost:3002

# Start frontend if needed
cd ../your-frontend-directory
npm start
```

### **❌ "Admin login failed"**
```bash
# Test login manually
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!"}'

# Check if user exists in database
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain \
  -c "SELECT email FROM app_users WHERE email = 'admin@bbp.dev';"
```

### **❌ "Database connection failed"**
```bash
# Test database connection
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain -c "SELECT 1;"

# Update database credentials in cypress.config.js if needed
```

### **❌ "UI elements not found"**
- Add `data-cy` attributes to your React components
- See **Frontend Preparation** section in INSTALLATION.md

---

## **📊 Expected Test Results**

### **✅ Successful Setup Should Show:**
```
Setup verification: ✅ Passed
Authentication API: ✅ 8 tests passed  
Sales Management API: ✅ 15 tests passed
Login & Navigation UI: ✅ 6 tests passed (if frontend running)
Order-to-Cash Flow: ✅ 3 workflow tests passed
```

### **⚠️ Partial Success (API Only):**
```
Setup verification: ✅ Passed
Authentication API: ✅ 8 tests passed
Sales Management API: ✅ 15 tests passed
UI Tests: ⚠️ Skipped (frontend not running)
```

---

## **🚀 Next Steps After Setup**

1. **Explore Interactive Mode**: `npm run cypress:open`
   - Watch tests run in real browser
   - Debug failing tests step-by-step
   - Record test videos and screenshots

2. **Add Your Own Tests**: 
   - Copy existing test patterns
   - Test your specific business rules
   - Add tests for new features

3. **CI/CD Integration**:
   - Use `npm run cypress:run` in pipelines
   - Set up test result reporting
   - Configure parallel test execution

4. **Frontend Enhancement**:
   - Add `data-cy` attributes to components
   - Expand UI test coverage
   - Test responsive design and accessibility

---

## **🎯 Success Metrics**

After quick setup, you should be able to:

- ✅ **Login as admin** via API and UI
- ✅ **Create dealers** via API and UI forms
- ✅ **Create sales orders** with GST calculation
- ✅ **Complete order workflow** (order → approval → shipment → invoice)
- ✅ **Verify database** changes and accounting entries
- ✅ **Handle errors** gracefully with proper messages

---

## **🆘 Need Help?**

If you're stuck:

1. **Run diagnostics**: `node setup-check.js`
2. **Check logs**: Look at terminal output and Cypress screenshots
3. **Verify services**: Ensure backend, database, and optionally frontend are running
4. **Read details**: Check INSTALLATION.md for comprehensive guide

**Time to setup: 2-5 minutes** ⏱️  
**Time to first successful test: 5-10 minutes** 🎯

**Happy Testing!** 🧪
