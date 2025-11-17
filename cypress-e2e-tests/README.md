# BigBright ERP - Cypress E2E Testing Suite

This comprehensive testing suite provides end-to-end testing for the BigBright ERP system, covering both API and UI testing scenarios.

## 🎯 **Test Coverage**

### **API Tests**
- **Authentication**: Login, registration, MFA, token management
- **Sales Management**: Dealers, orders, promotions, targets
- **Inventory Management**: Raw materials, finished goods, batches
- **Accounting**: Accounts, journal entries, ledgers
- **Factory Operations**: Production plans, batches, logs
- **HR & Payroll**: Employees, leave requests, payroll runs
- **Orchestration**: Order approval, dispatch, workflows

### **UI Tests**
- **User Interface**: Login, navigation, responsive design
- **Module Workflows**: Sales, inventory, accounting dashboards
- **Form Validation**: Error handling, field validation
- **Data Management**: CRUD operations via UI
- **User Experience**: Accessibility, keyboard navigation

### **Workflow Tests**
- **Order-to-Cash**: Complete sales cycle testing
- **Procure-to-Pay**: Purchase order to payment workflow
- **Inventory Management**: Stock movements and reservations
- **Production Planning**: Manufacturing workflows
- **Financial Reporting**: Accounting and ledger flows

## 🚀 **Quick Start**

### **Prerequisites**
1. **Backend Running**: ERP backend on `http://localhost:8080`
2. **Frontend Running**: React app on `http://localhost:3002`
3. **Database**: PostgreSQL with test data
4. **Node.js**: Version 16 or higher

### **Installation**
```bash
cd cypress-e2e-tests
npm install
```

### **Configuration**
Update `cypress.config.js` with your environment settings:

```javascript
env: {
  apiUrl: 'http://localhost:8080',
  adminEmail: 'admin@bbp.dev',
  adminPassword: 'ChangeMe123!',
  companyCode: 'BBP',
  // Database connection for test data management
  dbHost: 'localhost',
  dbPort: 5432,
  dbName: 'erp_domain',
  dbUser: 'erp',
  dbPassword: 'erp'
}
```

### **Running Tests**

#### **Interactive Mode (Recommended for Development)**
```bash
npm run cypress:open
```

#### **Headless Mode (CI/CD)**
```bash
# Run all tests
npm run cypress:run

# Run specific test suites
npm run test:api      # API tests only
npm run test:ui       # UI tests only
npm run test:workflows # Workflow tests only
```

## 📋 **Test Organization**

```
cypress/
├── e2e/
│   ├── api/                    # API endpoint tests
│   │   ├── 01-authentication.cy.js
│   │   ├── 02-sales-management.cy.js
│   │   ├── 03-inventory-management.cy.js
│   │   └── 04-accounting.cy.js
│   ├── ui/                     # User interface tests
│   │   ├── 01-login-and-navigation.cy.js
│   │   ├── 02-dealer-management.cy.js
│   │   └── 03-order-management.cy.js
│   └── workflows/              # End-to-end workflow tests
│       ├── 01-order-to-cash-flow.cy.js
│       └── 02-production-workflow.cy.js
├── support/
│   ├── commands.js             # Custom UI commands
│   ├── api-commands.js         # Custom API commands
│   └── e2e.js                  # Global configuration
├── plugins/
│   ├── db-tasks.js            # Database operations
│   ├── seed-tasks.js          # Test data seeding
│   └── cleanup-tasks.js       # Test data cleanup
└── fixtures/                   # Test data fixtures
```

## 🔧 **Key Features**

### **Custom Commands**

#### **UI Commands**
```javascript
// Login shortcuts
cy.loginAsAdmin()
cy.login(email, password, mfaCode)

// Navigation helpers
cy.navigateToModule('sales')
cy.switchCompany('BBP')

// Entity creation helpers
cy.createTestDealer(dealerData)
cy.createTestOrder(orderData)

// Validation helpers
cy.checkSuccessMessage('Order created')
cy.checkErrorMessage('Validation failed')
```

#### **API Commands**
```javascript
// Authentication
cy.apiLogin(email, password)
cy.apiSwitchCompany('BBP')

// Entity operations
cy.apiCreateDealer(dealerData)
cy.apiCreateOrder(orderData)
cy.apiApproveOrder(orderId)

// Data setup/cleanup
cy.setupTestData()
cy.cleanupTestData()
```

### **Database Integration**
```javascript
// Database queries
cy.task('queryDb', { 
  query: 'SELECT * FROM dealers WHERE code = ?', 
  params: ['TEST-001'] 
})

// Test data seeding
cy.task('seedTestData', { 
  dealerCount: 5, 
  orderCount: 10 
})

// Cleanup operations
cy.task('cleanupTestData', { 
  cleanupOrders: true,
  cleanupDealers: true 
})
```

## 📊 **Test Scenarios**

### **Critical Path Testing**

#### **1. Order-to-Cash Flow**
```
Order Creation → Inventory Check → Approval → Production (if needed) 
→ Shipment → Invoice Generation → Payment → Ledger Updates
```

#### **2. Inventory Management**
```
Raw Material Purchase → Production → Finished Goods → Reservation 
→ Dispatch → COGS Calculation → Stock Adjustments
```

#### **3. Financial Workflows**
```
Transaction Recording → Journal Entry Creation → Account Balance Updates 
→ Ledger Maintenance → Financial Reporting
```

### **Error Handling Testing**
- **Network Failures**: API timeouts, connection errors
- **Validation Errors**: Form validation, business rule violations
- **Authorization**: Permission checks, role-based access
- **Data Integrity**: Concurrency, transaction rollbacks

### **Performance Testing**
- **Large Dataset Handling**: Orders with many line items
- **Concurrent Operations**: Multiple users, inventory conflicts
- **Pagination**: Large result sets, infinite scroll
- **Search Performance**: Complex filtering, text search

## 🎛️ **Test Data Management**

### **Seeding Strategy**
```javascript
// Automatically create test data
const testData = await cy.task('seedTestData', {
  dealerCount: 10,
  productCount: 20,
  orderCount: 15,
  productionCount: 5
})
```

### **Cleanup Strategy**
```javascript
// Clean up after tests
beforeEach(() => {
  cy.task('cleanupTestData', { 
    testDataPrefix: 'CYPRESS' 
  })
})
```

### **Data Isolation**
- **Prefix-based**: All test data uses identifiable prefixes
- **Timestamp-based**: Unique identifiers with timestamps
- **Company Isolation**: Tests run in specific company context

## 🔍 **Debugging & Troubleshooting**

### **Common Issues**

#### **Authentication Failures**
```bash
# Check backend is running
curl http://localhost:8080/actuator/health

# Verify credentials
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!"}'
```

#### **Database Connection Issues**
```javascript
// Test database connectivity
cy.task('queryDb', { 
  query: 'SELECT 1 as test' 
}).then(result => {
  console.log('Database connected:', result)
})
```

#### **UI Element Not Found**
- Ensure frontend uses proper `data-cy` attributes
- Check for dynamic loading, use `cy.wait()`
- Verify viewport size affects element visibility

### **Test Debugging**
```javascript
// Add debugging information
cy.log('Debug: Current user state')
cy.window().its('currentUser').then(console.log)

// Pause test execution
cy.pause()

// Take screenshots
cy.screenshot('debug-state')
```

## 🚦 **CI/CD Integration**

### **GitHub Actions Example**
```yaml
name: E2E Tests
on: [push, pull_request]

jobs:
  e2e:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_DB: erp_domain
          POSTGRES_USER: erp
          POSTGRES_PASSWORD: erp
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '18'
          
      - name: Start Backend
        run: |
          cd erp-domain
          ./mvnw spring-boot:run &
          
      - name: Start Frontend
        run: |
          cd frontend
          npm install
          npm start &
          
      - name: Wait for services
        run: |
          npx wait-on http://localhost:8080/actuator/health
          npx wait-on http://localhost:3002
          
      - name: Run E2E Tests
        run: |
          cd cypress-e2e-tests
          npm install
          npm run cypress:run
          
      - name: Upload Screenshots
        uses: actions/upload-artifact@v3
        if: failure()
        with:
          name: cypress-screenshots
          path: cypress/screenshots/
```

## 📈 **Reporting & Analytics**

### **Test Results**
- **Cypress Dashboard**: Test run history, flaky test detection
- **Screenshots/Videos**: Automatic capture on failures
- **Test Coverage**: API endpoint coverage tracking
- **Performance Metrics**: Response times, load testing

### **Monitoring**
```javascript
// Track test execution metrics
cy.task('recordMetrics', {
  testName: 'order-creation',
  duration: testDuration,
  assertions: assertionCount,
  status: 'passed'
})
```

## 🛡️ **Best Practices**

### **Test Design**
1. **Independent Tests**: Each test should be able to run in isolation
2. **Data Management**: Clean setup and teardown of test data
3. **Stable Selectors**: Use `data-cy` attributes, avoid CSS selectors
4. **Error Handling**: Test both success and failure scenarios

### **Maintainability**
1. **Page Objects**: Encapsulate UI interactions in reusable functions
2. **Custom Commands**: Create domain-specific test commands
3. **Configuration**: Environment-based configuration management
4. **Documentation**: Keep tests self-documenting with clear names

### **Performance**
1. **Parallel Execution**: Run tests in parallel when possible
2. **Smart Waiting**: Use appropriate wait strategies
3. **Test Data**: Minimize database operations, use fixtures when possible
4. **Selective Testing**: Run relevant test suites based on changes

---

## 🚀 **Getting Started Checklist**

- [ ] Backend running on http://localhost:8080
- [ ] Frontend running on http://localhost:3002
- [ ] Database accessible and seeded with admin user
- [ ] Environment variables configured in cypress.config.js
- [ ] Dependencies installed with `npm install`
- [ ] Initial test run with `npm run cypress:open`
- [ ] Verify test data creation and cleanup works
- [ ] Review test output and screenshots

**Happy Testing!** 🎉

For support or questions, refer to the Cypress documentation or create an issue in the repository.
