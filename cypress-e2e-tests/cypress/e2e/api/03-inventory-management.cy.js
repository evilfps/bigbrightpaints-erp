describe('Inventory Management API Tests', () => {
  const testAccounts = {}
  let testFinishedGood

  before(() => {
    cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
    cy.apiSwitchCompany(Cypress.env('companyCode'))

    // Set up basic accounting accounts required for inventory valuation
    cy.apiCreateAccount({
      code: `INV-${Date.now()}`,
      name: 'Inventory Asset (Cypress)',
      type: 'ASSET'
    }).then(account => {
      testAccounts.inventory = account
    })

    cy.apiCreateAccount({
      code: `COGS-${Date.now()}`,
      name: 'COGS (Cypress)',
      type: 'EXPENSE'
    }).then(account => {
      testAccounts.cogs = account
    })

    cy.apiCreateAccount({
      code: `REV-${Date.now()}`,
      name: 'Revenue (Cypress)',
      type: 'REVENUE'
    }).then(account => {
      testAccounts.revenue = account
    })
  })

  it('should list existing finished goods', () => {
    cy.apiRequest({
      method: 'GET',
      url: '/api/v1/inventory/finished-goods'
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data).to.be.an('array')
    })
  })

  it('should create finished good and production batch', () => {
    const productCode = `INV-PAINT-${Date.now()}`

    // Create finished good definition
    cy.apiRequest({
      method: 'POST',
      url: '/api/v1/inventory/finished-goods',
      body: {
        productCode,
        name: 'Inventory Test Paint',
        unit: 'LITER',
        costingMethod: 'FIFO',
        valuationAccountId: testAccounts.inventory.id,
        cogsAccountId: testAccounts.cogs.id,
        revenueAccountId: testAccounts.revenue.id
      }
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      testFinishedGood = response.body.data
      expect(testFinishedGood).to.have.property('id')
      expect(testFinishedGood.productCode).to.eq(productCode)

      // Create an initial inventory batch for this finished good
      return cy.apiRequest({
        method: 'POST',
        url: '/api/v1/inventory/finished-good-batches',
        body: {
          finishedGoodId: testFinishedGood.id,
          batchCode: `BATCH-${Date.now()}`,
          quantity: 50,
          unitCost: 750,
          manufacturedAt: new Date().toISOString()
        }
      })
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true

      // Verify that finished good now appears in listing
      return cy.apiRequest({
        method: 'GET',
        url: '/api/v1/inventory/finished-goods'
      })
    }).then(response => {
      expect(response.status).to.eq(200)
      const created = response.body.data.find(item => item.id === testFinishedGood.id)
      expect(created).to.exist

      // Inventory DTOs may expose stock using different field names.
      const stockFields = ['currentStock', 'availableQuantity', 'quantityOnHand']
      const hasStockField = stockFields.some(field => created[field] !== undefined)
      expect(hasStockField, 'finished good should expose a stock field').to.be.true
    })
  })

  it('should validate finished good creation payload', () => {
    // Missing required fields like name / productCode should fail
    cy.apiRequest({
      method: 'POST',
      url: '/api/v1/inventory/finished-goods',
      body: {
        // Intentionally incomplete body
        unit: 'LITER'
      },
      failOnStatusCode: false
    }).then(response => {
      expect(response.status).to.be.oneOf([400, 422])
      expect(response.body.success).to.be.false
    })
  })
})

