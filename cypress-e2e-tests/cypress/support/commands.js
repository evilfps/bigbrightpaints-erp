// Custom commands for UI testing

/**
 * Login with credentials and handle MFA if needed
 */
Cypress.Commands.add('login', (email, password, mfaCode = null) => {
  cy.visit('/login')
  cy.get('[data-cy=email-input]').type(email)
  cy.get('[data-cy=password-input]').type(password)
  
  if (mfaCode) {
    cy.get('[data-cy=mfa-code-input]').type(mfaCode)
  }
  
  cy.get('[data-cy=login-button]').click()
  
  // Wait for successful login (dashboard or home page)
  cy.url().should('not.include', '/login')
  cy.get('[data-cy=user-menu]').should('be.visible')
})

/**
 * Login as admin user
 */
Cypress.Commands.add('loginAsAdmin', () => {
  cy.login(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
})

/**
 * Switch company context
 */
Cypress.Commands.add('switchCompany', (companyCode) => {
  cy.get('[data-cy=company-switcher]').click()
  cy.get(`[data-cy=company-option-${companyCode}]`).click()
  cy.get('[data-cy=current-company]').should('contain', companyCode)
})

/**
 * Navigate to a specific module
 */
Cypress.Commands.add('navigateToModule', (moduleName) => {
  const moduleSelectors = {
    sales: '[data-cy=nav-sales]',
    inventory: '[data-cy=nav-inventory]',
    accounting: '[data-cy=nav-accounting]',
    factory: '[data-cy=nav-factory]',
    hr: '[data-cy=nav-hr]',
    reports: '[data-cy=nav-reports]'
  }
  
  cy.get(moduleSelectors[moduleName]).click()
  cy.url().should('include', `/${moduleName}`)
})

/**
 * Create a test dealer
 */
Cypress.Commands.add('createTestDealer', (dealerData = {}) => {
  const defaultData = {
    name: 'Test Dealer',
    code: 'TD001',
    email: 'dealer@test.com',
    phone: '+91-9876543210',
    creditLimit: '100000'
  }
  
  const data = { ...defaultData, ...dealerData }
  
  cy.navigateToModule('sales')
  cy.get('[data-cy=dealers-tab]').click()
  cy.get('[data-cy=add-dealer-button]').click()
  
  cy.get('[data-cy=dealer-name-input]').type(data.name)
  cy.get('[data-cy=dealer-code-input]').type(data.code)
  cy.get('[data-cy=dealer-email-input]').type(data.email)
  cy.get('[data-cy=dealer-phone-input]').type(data.phone)
  cy.get('[data-cy=dealer-credit-limit-input]').type(data.creditLimit)
  
  cy.get('[data-cy=save-dealer-button]').click()
  cy.get('[data-cy=success-message]').should('be.visible')
  
  return cy.wrap(data)
})

/**
 * Create a test sales order
 */
Cypress.Commands.add('createTestOrder', (orderData = {}) => {
  const defaultData = {
    dealerId: null,
    items: [
      { productCode: 'PAINT-001', quantity: 10, unitPrice: 500 }
    ],
    notes: 'Test order created by Cypress'
  }
  
  const data = { ...defaultData, ...orderData }
  
  cy.navigateToModule('sales')
  cy.get('[data-cy=orders-tab]').click()
  cy.get('[data-cy=add-order-button]').click()
  
  if (data.dealerId) {
    cy.get('[data-cy=dealer-select]').select(data.dealerId)
  }
  
  // Add order items
  data.items.forEach((item, index) => {
    if (index > 0) {
      cy.get('[data-cy=add-item-button]').click()
    }
    cy.get(`[data-cy=item-product-${index}]`).type(item.productCode)
    cy.get(`[data-cy=item-quantity-${index}]`).type(item.quantity.toString())
    cy.get(`[data-cy=item-price-${index}]`).type(item.unitPrice.toString())
  })
  
  if (data.notes) {
    cy.get('[data-cy=order-notes]').type(data.notes)
  }
  
  cy.get('[data-cy=save-order-button]').click()
  cy.get('[data-cy=success-message]').should('be.visible')
  
  return cy.wrap(data)
})

/**
 * Wait for API call to complete
 */
Cypress.Commands.add('waitForApi', (alias) => {
  cy.wait(alias).then((interception) => {
    expect(interception.response.statusCode).to.be.oneOf([200, 201, 204])
  })
})

/**
 * Check for error messages
 */
Cypress.Commands.add('checkErrorMessage', (message) => {
  cy.get('[data-cy=error-message]').should('contain', message)
})

/**
 * Check for success messages
 */
Cypress.Commands.add('checkSuccessMessage', (message) => {
  cy.get('[data-cy=success-message]').should('contain', message)
})

/**
 * Logout
 */
Cypress.Commands.add('logout', () => {
  cy.get('[data-cy=user-menu]').click()
  cy.get('[data-cy=logout-button]').click()
  cy.url().should('include', '/login')
})
