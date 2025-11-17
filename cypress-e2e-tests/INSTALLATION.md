# 🚀 **INSTALLATION & CONFIGURATION GUIDE**

Follow these steps to set up the Cypress E2E testing suite for your BigBright ERP system.

## **STEP 1: Prerequisites Check** ✅

Run the prerequisites checker to verify your environment:

```bash
# Navigate to the test directory
cd cypress-e2e-tests

# Make the checker executable (Linux/Mac)
chmod +x setup-check.js

# Run prerequisites check
node setup-check.js
```

### **Required Prerequisites:**
- ✅ **Node.js 16+** (`node --version`)
- ✅ **npm** (`npm --version`)
- ✅ **Backend running** at `http://localhost:8080`
- ✅ **Database accessible** (PostgreSQL)
- ⚠️ **Frontend running** at `http://localhost:3002` (for UI tests)

---

## **STEP 2: Install Dependencies** 📦

```bash
# Navigate to test directory
cd cypress-e2e-tests

# Install all dependencies
npm install

# Verify Cypress installation
npx cypress verify
```

**Expected output:**
```
✅ Verified Cypress!
Cypress Version: 13.6.0
```

---

## **STEP 3: Configuration Setup** ⚙️

### **A. Update cypress.config.js**

```javascript
// cypress.config.js
const { defineConfig } = require('cypress')

module.exports = defineConfig({
  e2e: {
    baseUrl: 'http://localhost:3002', // 👈 Your React frontend URL
    env: {
      // 👇 Update these with your actual settings
      apiUrl: 'http://localhost:8080',     // Your backend URL
      adminEmail: 'admin@bbp.dev',         // Admin user email
      adminPassword: 'ChangeMe123!',       // Admin password
      companyCode: 'BBP',                  // Default company code
      
      // 👇 Database connection (update if different)
      dbHost: 'localhost',
      dbPort: 5432,
      dbName: 'erp_domain',               // Your database name
      dbUser: 'erp',                      // Database username
      dbPassword: 'erp'                   // Database password
    }
  }
})
```

### **B. Create Environment-Specific Configs**

Create different config files for different environments:

```bash
# Development config
cp cypress.config.js cypress.config.dev.js

# Production config
cp cypress.config.js cypress.config.prod.js
```

**cypress.config.prod.js** (for testing against production-like environment):
```javascript
module.exports = defineConfig({
  e2e: {
    baseUrl: 'http://your-production-frontend.com',
    env: {
      apiUrl: 'http://your-production-api.com',
      adminEmail: 'admin@your-company.com',
      adminPassword: 'your-secure-password',
      // ... other production settings
    }
  }
})
```

---

## **STEP 4: Frontend Preparation** 🎨

For UI tests to work, your React components need `data-cy` attributes.

### **A. Add data-cy Attributes**

Update your React components with test selectors:

```jsx
// ❌ Before (hard to test)
<button onClick={handleLogin}>Login</button>
<input type="email" placeholder="Email" />

// ✅ After (easy to test)
<button data-cy="login-button" onClick={handleLogin}>Login</button>
<input data-cy="email-input" type="email" placeholder="Email" />
```

### **B. Common Components to Update**

**Login Form:**
```jsx
<form data-cy="login-form">
  <input data-cy="email-input" type="email" />
  <input data-cy="password-input" type="password" />
  <input data-cy="mfa-code-input" type="text" />
  <button data-cy="login-button">Login</button>
  <div data-cy="error-message" className="error">{error}</div>
</form>
```

**Navigation:**
```jsx
<nav data-cy="main-navigation">
  <div data-cy="user-menu">
    <span data-cy="current-user">{user.email}</span>
    <button data-cy="logout-button">Logout</button>
  </div>
  <div data-cy="company-switcher">
    <span data-cy="current-company">{currentCompany}</span>
  </div>
  <ul>
    <li><a data-cy="nav-sales" href="/sales">Sales</a></li>
    <li><a data-cy="nav-inventory" href="/inventory">Inventory</a></li>
    <li><a data-cy="nav-accounting" href="/accounting">Accounting</a></li>
  </ul>
</nav>
```

**Data Tables:**
```jsx
<div data-cy="dealers-table">
  <button data-cy="add-dealer-button">Add Dealer</button>
  <input data-cy="dealers-search" placeholder="Search dealers..." />
  <table>
    <tbody>
      {dealers.map(dealer => (
        <tr key={dealer.id}>
          <td>{dealer.name}</td>
          <td>
            <button data-cy="edit-dealer-button">Edit</button>
            <button data-cy="view-dealer-button">View</button>
          </td>
        </tr>
      ))}
    </tbody>
  </table>
</div>
```

**Forms:**
```jsx
<form data-cy="dealer-form">
  <input data-cy="dealer-name-input" />
  <input data-cy="dealer-code-input" />
  <input data-cy="dealer-email-input" />
  <input data-cy="dealer-credit-limit-input" />
  <button data-cy="save-dealer-button">Save</button>
  <div data-cy="name-error" className="error">{nameError}</div>
</form>
```

### **C. Message Components**
```jsx
// Success/Error Messages
<div data-cy="success-message" className="alert-success">
  {successMessage}
</div>
<div data-cy="error-message" className="alert-error">
  {errorMessage}
</div>
```

---

## **STEP 5: Backend Verification** 🔍

Ensure your backend has the expected endpoints:

### **A. Test API Access**

```bash
# Test backend health
curl http://localhost:8080/actuator/health

# Test admin login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!"}'

# Expected response: {"success":true,"data":{"token":"...","user":{...}}}
```

### **B. Verify Required Endpoints Exist**

The tests expect these endpoints to be available:

```bash
# Authentication
POST /api/v1/auth/login
POST /api/v1/auth/register  
POST /api/v1/auth/refresh-token
GET  /api/v1/auth/profile
POST /api/v1/auth/mfa/setup

# Sales
GET  /api/v1/sales/dealers
POST /api/v1/sales/dealers
GET  /api/v1/sales/orders
POST /api/v1/sales/orders

# Accounting
GET  /api/v1/accounting/accounts
POST /api/v1/accounting/accounts
POST /api/v1/accounting/journal-entries

# Orchestration
POST /api/v1/orchestrator/orders/{id}/approve
POST /api/v1/orchestrator/orders/{id}/fulfillment
```

---

## **STEP 6: Database Setup** 🗄️

### **A. Test Database Connection**

```bash
# Test PostgreSQL connection
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain -c "SELECT COUNT(*) FROM companies;"

# Expected: Should return count of companies
```

### **B. Verify Required Tables Exist**

```sql
-- Check if key tables exist
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public' 
AND table_name IN (
  'companies', 'app_users', 'dealers', 'sales_orders', 
  'accounts', 'journal_entries', 'finished_goods'
);
```

### **C. Ensure Admin User Exists**

```sql
-- Check admin user exists
SELECT email, enabled FROM app_users WHERE email = 'admin@bbp.dev';

-- If not exists, create it (adjust as needed)
INSERT INTO app_users (email, password_hash, display_name, enabled) 
VALUES ('admin@bbp.dev', '$2a$10$...', 'Admin User', true);
```

---

## **STEP 7: First Test Run** 🎯

### **A. Interactive Mode (Recommended)**

```bash
# Open Cypress Test Runner
npm run cypress:open

# Or use specific config
npx cypress open --config-file cypress.config.dev.js
```

This will open the Cypress GUI where you can:
1. Select a browser (Chrome recommended)
2. Choose test files to run
3. Watch tests execute in real-time

### **B. Headless Mode**

```bash
# Run all tests
npm run cypress:run

# Run specific test types
npm run test:api      # API tests only
npm run test:ui       # UI tests only  
npm run test:workflows # Workflow tests only

# Run with custom config
npx cypress run --config-file cypress.config.prod.js
```

### **C. Using the Shell Script**

```bash
# Make executable (Linux/Mac)
chmod +x run-tests.sh

# Run all tests with checks
./run-tests.sh

# Run specific test types
./run-tests.sh api
./run-tests.sh ui

# Skip prerequisite checks (faster)
./run-tests.sh all true
```

---

## **STEP 8: Verify Everything Works** ✅

Run this verification sequence:

### **A. API Tests First**
```bash
npm run test:api
```
**Expected:** All authentication and basic API tests should pass.

### **B. Simple UI Test**
```bash
npx cypress run --spec "cypress/e2e/ui/01-login-and-navigation.cy.js"
```
**Expected:** Login and basic navigation should work.

### **C. Database Integration**
```bash
npx cypress run --spec "cypress/e2e/workflows/01-order-to-cash-flow.cy.js"
```
**Expected:** Complete workflow including database operations.

---

## **🔧 TROUBLESHOOTING**

### **Common Issues & Solutions**

#### **1. "Backend not accessible"**
```bash
# Check if backend is running
curl http://localhost:8080/actuator/health

# If not running, start it
cd ../erp-domain
./mvnw spring-boot:run

# Check different ports
curl http://localhost:8081/actuator/health
```

#### **2. "Frontend not accessible"**  
```bash
# Check if frontend is running
curl http://localhost:3002

# If not running, start it
cd ../FRONTEND\ OF\ BACKEND  # or your frontend directory
npm start

# Update baseUrl in cypress.config.js if using different port
```

#### **3. "Database connection failed"**
```bash
# Check PostgreSQL is running
pg_ctl status

# Test connection manually
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain

# Update database credentials in cypress.config.js
```

#### **4. "Admin login failed"**
```bash
# Test login manually
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!"}'

# Check user exists in database
PGPASSWORD=erp psql -h localhost -U erp -d erp_domain \
  -c "SELECT email FROM app_users WHERE email = 'admin@bbp.dev';"
```

#### **5. "UI elements not found"**
- Make sure you added `data-cy` attributes to your React components
- Check console for element selector errors
- Use Cypress Test Runner to inspect DOM

#### **6. "Tests are too slow"**
```javascript
// Increase timeouts in cypress.config.js
module.exports = defineConfig({
  e2e: {
    defaultCommandTimeout: 10000,    // 10 seconds
    requestTimeout: 10000,           // 10 seconds
    responseTimeout: 10000           // 10 seconds
  }
})
```

#### **7. "Test data conflicts"**
```bash
# Clean up test data manually
npm run cypress:run --spec "**/cleanup.cy.js"

# Or run cleanup task directly
node -e "
  const cleanup = require('./cypress/plugins/cleanup-tasks.js');
  cleanup({ cleanupOrders: true, cleanupDealers: true });
"
```

---

## **📚 Next Steps**

Once installation is complete:

1. **📝 Customize Tests**: Update test data and scenarios for your business rules
2. **🎨 Add More UI Tests**: Create tests for your specific components
3. **🔄 CI/CD Integration**: Set up automated testing in your pipeline
4. **📊 Monitoring**: Set up test result reporting and notifications
5. **🧪 Advanced Scenarios**: Add performance tests, security tests

---

## **💡 Tips for Success**

1. **Start Small**: Begin with API tests, then move to UI tests
2. **Test Data Strategy**: Use the seeding/cleanup utilities to manage test data
3. **Debugging**: Use `cy.pause()` and `cy.debug()` to troubleshoot tests
4. **Screenshots**: Failed tests automatically capture screenshots
5. **Parallel Testing**: Run tests in parallel for faster feedback

---

## **🆘 Need Help?**

If you encounter issues:

1. **Check Prerequisites**: Run `node setup-check.js` again
2. **Review Logs**: Check Cypress console output and backend logs
3. **Database State**: Verify test data setup and cleanup
4. **Network**: Ensure all services are accessible
5. **Configuration**: Double-check all URLs and credentials

**Happy Testing!** 🎉
