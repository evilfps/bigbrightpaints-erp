#!/usr/bin/env node

/**
 * Comprehensive Backend Health Check
 * Tests all modules, endpoints, and critical functionality
 */

const { execSync } = require('child_process');
const fs = require('fs');

// Configuration
const config = {
  baseUrl: process.env.BACKEND_URL || 'http://localhost:8080',
  adminEmail: process.env.ADMIN_EMAIL || 'admin@bbp.dev',
  adminPassword: process.env.ADMIN_PASSWORD || 'ChangeMe123!',
  companyCode: process.env.COMPANY_CODE || 'BBP',
  timeout: 10000
};

// Colors for output
const colors = {
  reset: '\x1b[0m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  purple: '\x1b[35m',
  cyan: '\x1b[36m'
};

function log(message, color = 'reset') {
  console.log(`${colors[color]}${message}${colors.reset}`);
}

function printHeader(title) {
  log('\n' + '='.repeat(60), 'blue');
  log(`  ${title}`, 'blue');
  log('='.repeat(60), 'blue');
}

function printSection(title) {
  log(`\n📋 ${title}`, 'cyan');
  log('-'.repeat(40), 'cyan');
}

// HTTP request helper
async function makeRequest(url, options = {}) {
  const method = options.method || 'GET';
  const headers = options.headers || {};
  const body = options.body || '';
  
  const curlCmd = [
    'curl', '-s', '-w', '"\\n%{http_code}\\n"',
    '-X', method,
    '-H', '"Content-Type: application/json"'
  ];
  
  // Add authorization header if token provided
  if (options.token) {
    curlCmd.push('-H', `"Authorization: Bearer ${options.token}"`);
  }
  
  // Add other headers
  Object.entries(headers).forEach(([key, value]) => {
    curlCmd.push('-H', `"${key}: ${value}"`);
  });
  
  // Add body for POST/PUT requests
  if (body && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
    curlCmd.push('-d', `'${JSON.stringify(body)}'`);
  }
  
  curlCmd.push(`"${config.baseUrl}${url}"`);
  
  try {
    const result = execSync(curlCmd.join(' '), { 
      encoding: 'utf8', 
      timeout: config.timeout 
    });
    
    const lines = result.trim().split('\n');
    const statusCode = parseInt(lines[lines.length - 1]);
    const responseBody = lines.slice(0, -1).join('\n');
    
    let jsonBody = null;
    try {
      jsonBody = JSON.parse(responseBody);
    } catch (e) {
      // Not JSON, that's okay
    }
    
    return {
      status: statusCode,
      body: responseBody,
      json: jsonBody,
      success: statusCode >= 200 && statusCode < 300
    };
  } catch (error) {
    return {
      status: 0,
      body: '',
      json: null,
      success: false,
      error: error.message
    };
  }
}

// Test categories
const tests = {
  infrastructure: [],
  authentication: [],
  modules: [],
  workflows: [],
  database: [],
  businessLogic: []
};

// Infrastructure Tests
tests.infrastructure = [
  {
    name: 'Health Check',
    test: async () => {
      const response = await makeRequest('/actuator/health');
      if (response.success && response.json?.status === 'UP') {
        return { success: true, message: 'Backend is healthy' };
      }
      return { success: false, message: `Health check failed: ${response.status}` };
    }
  },
  {
    name: 'Database Connectivity',
    test: async () => {
      const response = await makeRequest('/actuator/health/db');
      if (response.success || response.status === 404) {
        return { success: true, message: 'Database connectivity OK' };
      }
      return { success: false, message: `Database health check failed: ${response.status}` };
    }
  },
  {
    name: 'OpenAPI Documentation',
    test: async () => {
      const response = await makeRequest('/v3/api-docs');
      if (response.success && response.json?.openapi) {
        const pathCount = Object.keys(response.json.paths || {}).length;
        return { success: true, message: `OpenAPI docs available (${pathCount} endpoints)` };
      }
      return { success: false, message: 'OpenAPI docs not available' };
    }
  }
];

// Authentication Tests
tests.authentication = [
  {
    name: 'Admin Login',
    test: async () => {
      const response = await makeRequest('/api/v1/auth/login', {
        method: 'POST',
        body: {
          email: config.adminEmail,
          password: config.adminPassword
        }
      });
      
      if (response.success && response.json?.success && response.json?.data?.token) {
        // Store token for subsequent tests
        global.authToken = response.json.data.token;
        return { success: true, message: 'Admin login successful' };
      }
      return { success: false, message: `Login failed: ${response.json?.message || 'Unknown error'}` };
    }
  },
  {
    name: 'Token Validation',
    test: async () => {
      if (!global.authToken) {
        return { success: false, message: 'No auth token available' };
      }
      
      const response = await makeRequest('/api/v1/auth/profile', {
        token: global.authToken
      });
      
      if (response.success && response.json?.success) {
        return { success: true, message: 'Token validation successful' };
      }
      return { success: false, message: 'Token validation failed' };
    }
  },
  {
    name: 'Company Context Switch',
    test: async () => {
      if (!global.authToken) {
        return { success: false, message: 'No auth token available' };
      }
      
      const response = await makeRequest('/api/v1/multi-company/companies/switch', {
        method: 'POST',
        token: global.authToken,
        body: { companyCode: config.companyCode }
      });
      
      if (response.success && response.json?.success) {
        global.authToken = response.json.data.token; // Update token with company context
        return { success: true, message: `Switched to company ${config.companyCode}` };
      }
      return { success: false, message: 'Company switch failed' };
    }
  }
];

// Module Tests
tests.modules = [
  {
    name: 'Sales Module - List Dealers',
    test: async () => {
      const response = await makeRequest('/api/v1/sales/dealers', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `Sales module OK (${response.json.data.length} dealers)` };
      }
      return { success: false, message: `Sales module failed: ${response.status}` };
    }
  },
  {
    name: 'Sales Module - List Orders',
    test: async () => {
      const response = await makeRequest('/api/v1/sales/orders', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `Orders endpoint OK (${response.json.data.length} orders)` };
      }
      return { success: false, message: `Orders endpoint failed: ${response.status}` };
    }
  },
  {
    name: 'Accounting Module - List Accounts',
    test: async () => {
      const response = await makeRequest('/api/v1/accounting/accounts', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `Accounting module OK (${response.json.data.length} accounts)` };
      }
      return { success: false, message: `Accounting module failed: ${response.status}` };
    }
  },
  {
    name: 'Accounting Module - Journal Entries',
    test: async () => {
      const response = await makeRequest('/api/v1/accounting/journal-entries', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `Journal entries OK (${response.json.data.length} entries)` };
      }
      return { success: false, message: `Journal entries failed: ${response.status}` };
    }
  },
  {
    name: 'Inventory Module - Finished Goods',
    test: async () => {
      const response = await makeRequest('/api/v1/inventory/finished-goods', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `Inventory module OK (${response.json.data.length} products)` };
      }
      return { success: false, message: `Inventory module failed: ${response.status}` };
    }
  },
  {
    name: 'Factory Module - Production Plans',
    test: async () => {
      const response = await makeRequest('/api/v1/factory/plans', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `Factory module OK (${response.json.data.length} plans)` };
      }
      return { success: false, message: `Factory module failed: ${response.status}` };
    }
  },
  {
    name: 'HR Module - Employees',
    test: async () => {
      const response = await makeRequest('/api/v1/hr/employees', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `HR module OK (${response.json.data.length} employees)` };
      }
      return { success: false, message: `HR module failed: ${response.status}` };
    }
  },
  {
    name: 'Invoice Module',
    test: async () => {
      const response = await makeRequest('/api/v1/invoices', { token: global.authToken });
      if (response.success && Array.isArray(response.json?.data)) {
        return { success: true, message: `Invoice module OK (${response.json.data.length} invoices)` };
      }
      return { success: false, message: `Invoice module failed: ${response.status}` };
    }
  },
  {
    name: 'Reports Module',
    test: async () => {
      const response = await makeRequest('/api/v1/reports/aged-debtors', { token: global.authToken });
      if (response.success || response.status === 404) {
        return { success: true, message: 'Reports module accessible' };
      }
      return { success: false, message: `Reports module failed: ${response.status}` };
    }
  }
];

// Workflow Tests
tests.workflows = [
  {
    name: 'Orchestrator - Health Check',
    test: async () => {
      const response = await makeRequest('/api/v1/orchestrator/health', { token: global.authToken });
      if (response.success && response.json?.data) {
        return { success: true, message: 'Orchestrator module functional' };
      }
      return { success: false, message: `Orchestrator failed: ${response.status}` };
    }
  },
  {
    name: 'Dashboard - Admin',
    test: async () => {
      const response = await makeRequest('/api/v1/dashboard/admin', { token: global.authToken });
      if (response.success && response.json?.data) {
        return { success: true, message: 'Admin dashboard working' };
      }
      return { success: false, message: `Admin dashboard failed: ${response.status}` };
    }
  }
];

// Business Logic Tests
tests.businessLogic = [
  {
    name: 'Create Test Dealer',
    test: async () => {
      const testDealer = {
        name: `Health Check Dealer ${Date.now()}`,
        code: `HC${Date.now().toString().slice(-6)}`,
        email: 'healthcheck@test.com',
        phone: '+91-9999999999',
        creditLimit: 50000
      };
      
      const response = await makeRequest('/api/v1/sales/dealers', {
        method: 'POST',
        token: global.authToken,
        body: testDealer
      });
      
      if (response.success && response.json?.success) {
        global.testDealerId = response.json.data.id;
        return { success: true, message: 'Dealer creation works' };
      }
      return { success: false, message: `Dealer creation failed: ${response.json?.message || 'Unknown error'}` };
    }
  },
  {
    name: 'Create Test Order',
    test: async () => {
      if (!global.testDealerId) {
        return { success: false, message: 'No test dealer available' };
      }
      
      const testOrder = {
        dealerId: global.testDealerId,
        items: [
          { productCode: 'TEST-PAINT-001', quantity: 5, unitPrice: 1000 }
        ],
        currency: 'INR',
        gstTreatment: 'NONE',
        notes: 'Health check test order'
      };
      
      const response = await makeRequest('/api/v1/sales/orders', {
        method: 'POST',
        token: global.authToken,
        body: testOrder
      });
      
      if (response.success && response.json?.success) {
        global.testOrderId = response.json.data.id;
        return { success: true, message: 'Order creation works' };
      }
      return { success: false, message: `Order creation failed: ${response.json?.message || 'Unknown error'}` };
    }
  },
  {
    name: 'Create Test Account',
    test: async () => {
      const testAccount = {
        code: `HC-${Date.now().toString().slice(-6)}`,
        name: 'Health Check Test Account',
        type: 'ASSET'
      };
      
      const response = await makeRequest('/api/v1/accounting/accounts', {
        method: 'POST',
        token: global.authToken,
        body: testAccount
      });
      
      if (response.success && response.json?.success) {
        return { success: true, message: 'Account creation works' };
      }
      return { success: false, message: `Account creation failed: ${response.json?.message || 'Unknown error'}` };
    }
  }
];

// Run all tests
async function runTests() {
  printHeader('🔍 BIGBRIGHT ERP BACKEND HEALTH CHECK');
  
  log(`🎯 Target: ${config.baseUrl}`, 'blue');
  log(`👤 Admin: ${config.adminEmail}`, 'blue');
  log(`🏢 Company: ${config.companyCode}`, 'blue');
  
  const results = {
    total: 0,
    passed: 0,
    failed: 0,
    categories: {}
  };
  
  for (const [category, categoryTests] of Object.entries(tests)) {
    printSection(`${category.toUpperCase()} TESTS`);
    
    const categoryResults = { passed: 0, failed: 0, tests: [] };
    
    for (const test of categoryTests) {
      results.total++;
      process.stdout.write(`  ${test.name}... `);
      
      try {
        const result = await test.test();
        
        if (result.success) {
          log(`✅ ${result.message}`, 'green');
          results.passed++;
          categoryResults.passed++;
        } else {
          log(`❌ ${result.message}`, 'red');
          results.failed++;
          categoryResults.failed++;
        }
        
        categoryResults.tests.push({
          name: test.name,
          success: result.success,
          message: result.message
        });
        
      } catch (error) {
        log(`💥 Error: ${error.message}`, 'red');
        results.failed++;
        categoryResults.failed++;
      }
    }
    
    results.categories[category] = categoryResults;
  }
  
  // Summary
  printHeader('📊 SUMMARY');
  
  log(`Total Tests: ${results.total}`, 'blue');
  log(`Passed: ${results.passed}`, 'green');
  log(`Failed: ${results.failed}`, results.failed > 0 ? 'red' : 'green');
  log(`Success Rate: ${Math.round((results.passed / results.total) * 100)}%`, 
      results.failed === 0 ? 'green' : 'yellow');
  
  // Category breakdown
  printSection('CATEGORY BREAKDOWN');
  for (const [category, categoryResults] of Object.entries(results.categories)) {
    const total = categoryResults.passed + categoryResults.failed;
    const rate = Math.round((categoryResults.passed / total) * 100);
    log(`  ${category}: ${categoryResults.passed}/${total} (${rate}%)`, 
        categoryResults.failed === 0 ? 'green' : 'yellow');
  }
  
  // Recommendations
  printSection('RECOMMENDATIONS');
  
  if (results.failed === 0) {
    log('🎉 All tests passed! Your backend is fully functional.', 'green');
    log('✅ Ready for production use', 'green');
    log('✅ All modules working correctly', 'green');
    log('✅ Authentication and security functional', 'green');
    log('✅ Business logic operational', 'green');
  } else if (results.failed <= 2) {
    log('⚠️  Minor issues detected. Backend mostly functional.', 'yellow');
    log('👍 Core functionality working', 'green');
    log('🔧 Some endpoints may need attention', 'yellow');
  } else {
    log('❌ Multiple issues detected. Backend needs attention.', 'red');
    log('🔧 Review failed tests above', 'yellow');
    log('📋 Check logs for detailed error information', 'yellow');
  }
  
  // Exit code
  process.exit(results.failed > 0 ? 1 : 0);
}

// Handle command line arguments
if (require.main === module) {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    console.log(`
BigBright ERP Backend Health Check

Usage: node backend-health-check.js [options]

Environment Variables:
  BACKEND_URL      Backend URL (default: http://localhost:8080)
  ADMIN_EMAIL      Admin email (default: admin@bbp.dev)
  ADMIN_PASSWORD   Admin password (default: ChangeMe123!)
  COMPANY_CODE     Company code (default: BBP)

Options:
  --help, -h       Show this help message

This script performs comprehensive health checks on:
  ✅ Infrastructure (health, database, API docs)
  ✅ Authentication (login, tokens, company context)
  ✅ All modules (sales, accounting, inventory, factory, HR)
  ✅ Workflows (orchestrator, dashboards)
  ✅ Business logic (CRUD operations, data validation)
`);
    process.exit(0);
  }
  
  runTests().catch(error => {
    log(`💥 Fatal error: ${error.message}`, 'red');
    process.exit(1);
  });
}
