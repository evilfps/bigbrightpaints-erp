describe('Production Planning Workflow', () => {
  let productionPlan
  let productionBatch

  before(() => {
    cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
    cy.apiSwitchCompany(Cypress.env('companyCode'))
  })

  it('should create a production plan and log a batch', () => {
    const today = new Date().toISOString().substring(0, 10)
    const planNumber = `PLAN-${Date.now()}`

    // Step 1: Create a production plan
    cy.apiRequest({
      method: 'POST',
      url: '/api/v1/factory/production-plans',
      body: {
        planNumber,
        productName: 'Cypress Workflow Product',
        quantity: 100,
        plannedDate: today,
        notes: 'Cypress production workflow test plan'
      }
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true

      productionPlan = response.body.data
      expect(productionPlan).to.have.property('id')
      expect(productionPlan.planNumber).to.eq(planNumber)

      // Step 2: Verify plan appears in list
      return cy.apiRequest({
        method: 'GET',
        url: '/api/v1/factory/production-plans'
      })
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data).to.be.an('array')

      const foundPlan = response.body.data.find(plan => plan.id === productionPlan.id)
      expect(foundPlan).to.exist

      // Step 3: Log a production batch for this plan
      return cy.apiRequest({
        method: 'POST',
        url: '/api/v1/factory/production-batches',
        qs: { planId: productionPlan.id },
        body: {
          batchNumber: `BATCH-${Date.now()}`,
          quantityProduced: 100,
          loggedBy: 'Cypress Tester',
          notes: 'Cypress production batch'
        }
      })
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true

      productionBatch = response.body.data
      expect(productionBatch).to.have.property('id')

      // Step 4: Verify batch appears in batch listing
      return cy.apiRequest({
        method: 'GET',
        url: '/api/v1/factory/production-batches'
      })
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data).to.be.an('array')

      const foundBatch = response.body.data.find(batch => batch.id === productionBatch.id)
      expect(foundBatch).to.exist
    })
  })
})

