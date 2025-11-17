// Import commands
import './commands'
import './api-commands'

// Global configuration
Cypress.on('uncaught:exception', (err, runnable) => {
  // Prevent Cypress from failing on uncaught exceptions
  if (err.message.includes('ResizeObserver loop limit exceeded')) {
    return false
  }
  if (err.message.includes('Non-Error promise rejection captured')) {
    return false
  }
  return true
})

// Global hooks
beforeEach(() => {
  // Set up common data or state before each test
  cy.log('Setting up test environment')
})

afterEach(() => {
  // Cleanup after each test if needed
  cy.log('Test completed')
})

// Custom assertions
chai.Assertion.addMethod('beValidUUID', function () {
  const uuid = this._obj
  const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  this.assert(
    uuidRegex.test(uuid),
    'expected #{this} to be a valid UUID',
    'expected #{this} not to be a valid UUID',
    uuid
  )
})
