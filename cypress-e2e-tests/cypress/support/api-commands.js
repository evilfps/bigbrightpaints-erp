// Custom commands for API testing

/**
 * Login via API and return token
 */
Cypress.Commands.add('apiLogin', (email, password) => {
  return cy.request({
    method: 'POST',
    url: `${Cypress.env('apiUrl')}/api/v1/auth/login`,
    body: {
      email,
      password
    }
  }).then((response) => {
    expect(response.status).to.eq(200)
    expect(response.body).to.have.property('success', true)
    expect(response.body.data).to.have.property('token')
    
    // Store token for subsequent requests
    const token = response.body.data.token
    Cypress.env('authToken', token)
    
    return token
  })
})

/**
 * Make authenticated API request
 */
Cypress.Commands.add('apiRequest', (options) => {
  const token = Cypress.env('authToken')
  
  const requestOptions = {
    ...options,
    url: `${Cypress.env('apiUrl')}${options.url}`,
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...options.headers
    }
  }
  
  return cy.request(requestOptions)
})

/**
 * Switch company via API
 */
Cypress.Commands.add('apiSwitchCompany', (companyCode) => {
  return cy.apiRequest({
    method: 'POST',
    url: '/api/v1/multi-company/companies/switch',
    body: { companyCode }
  }).then((response) => {
    expect(response.status).to.eq(200)
    // Update stored token
    Cypress.env('authToken', response.body.data.token)
    return response.body.data.token
  })
})

/**
 * Create dealer via API
 */
Cypress.Commands.add('apiCreateDealer', (dealerData) => {
  const defaultData = {
    name: 'API Test Dealer',
    code: `API-${Date.now()}`,
    email: 'api-dealer@test.com',
    phone: '+91-9876543210',
    creditLimit: 100000
  }
  
  const data = { ...defaultData, ...dealerData }
  
  return cy.apiRequest({
    method: 'POST',
    url: '/api/v1/sales/dealers',
    body: data
  }).then((response) => {
    expect(response.status).to.eq(200)
    expect(response.body.success).to.be.true
    return response.body.data
  })
})

/**
 * Create sales order via API
 */
Cypress.Commands.add('apiCreateOrder', (orderData) => {
  const defaultData = {
    dealerId: null,
    items: [
      { productCode: 'PAINT-001', quantity: 10, unitPrice: 500 }
    ],
    currency: 'INR',
    gstTreatment: 'NONE',
    notes: 'API test order'
  }
  
  const data = { ...defaultData, ...orderData }
  
  return cy.apiRequest({
    method: 'POST',
    url: '/api/v1/sales/orders',
    body: data
  }).then((response) => {
    expect(response.status).to.eq(200)
    expect(response.body.success).to.be.true
    return response.body.data
  })
})

/**
 * Approve order via orchestrator API
 */
Cypress.Commands.add('apiApproveOrder', (orderId, approverName = 'Test Approver') => {
  return cy.apiRequest({
    method: 'POST',
    url: `/api/v1/orchestrator/orders/${orderId}/approve`,
    body: {
      orderId: orderId.toString(),
      approvedBy: approverName,
      totalAmount: 5000
    }
  }).then((response) => {
    expect(response.status).to.eq(200)
    expect(response.body.success).to.be.true
    return response.body.data.traceId
  })
})

/**
 * Get order details via API
 */
Cypress.Commands.add('apiGetOrder', (orderId) => {
  return cy.apiRequest({
    method: 'GET',
    url: `/api/v1/sales/orders`
  }).then((response) => {
    expect(response.status).to.eq(200)
    const order = response.body.data.find(o => o.id === orderId)
    return order
  })
})

/**
 * Create account via API
 */
Cypress.Commands.add('apiCreateAccount', (accountData) => {
  const defaultData = {
    code: `TEST-${Date.now()}`,
    name: 'Test Account',
    type: 'ASSET'
  }
  
  const data = { ...defaultData, ...accountData }
  
  return cy.apiRequest({
    method: 'POST',
    url: '/api/v1/accounting/accounts',
    body: data
  }).then((response) => {
    expect(response.status).to.eq(200)
    return response.body.data
  })
})

/**
 * Create journal entry via API
 */
Cypress.Commands.add('apiCreateJournalEntry', (journalData) => {
  return cy.apiRequest({
    method: 'POST',
    url: '/api/v1/accounting/journal-entries',
    body: journalData
  }).then((response) => {
    expect(response.status).to.eq(200)
    return response.body.data
  })
})

/**
 * Setup test data via API
 */
Cypress.Commands.add('setupTestData', () => {
  return cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
    .then(() => cy.apiSwitchCompany(Cypress.env('companyCode')))
    .then(() => {
      // Create test accounts
      return cy.apiCreateAccount({ code: 'CASH', name: 'Cash Account', type: 'ASSET' })
    })
    .then((cashAccount) => {
      Cypress.env('testCashAccount', cashAccount)
      return cy.apiCreateAccount({ code: 'SALES', name: 'Sales Revenue', type: 'REVENUE' })
    })
    .then((salesAccount) => {
      Cypress.env('testSalesAccount', salesAccount)
      return cy.apiCreateDealer()
    })
    .then((dealer) => {
      Cypress.env('testDealer', dealer)
    })
})

/**
 * Cleanup test data
 */
Cypress.Commands.add('cleanupTestData', () => {
  // This would typically clean up test data created during tests
  // Implementation depends on your cleanup strategy
  cy.log('Cleaning up test data')
})
