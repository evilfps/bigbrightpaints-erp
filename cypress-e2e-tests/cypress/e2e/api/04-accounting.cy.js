describe('Accounting API Tests', () => {
  const testAccounts = {}
  let testJournalEntry

  before(() => {
    cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
    cy.apiSwitchCompany(Cypress.env('companyCode'))

    // Ensure we have some basic accounts to work with
    cy.apiCreateAccount({
      code: `CASH-${Date.now()}`,
      name: 'Cypress Cash Account',
      type: 'ASSET'
    }).then(account => {
      testAccounts.cash = account
    })

    cy.apiCreateAccount({
      code: `SALES-${Date.now()}`,
      name: 'Cypress Sales Revenue',
      type: 'REVENUE'
    }).then(account => {
      testAccounts.sales = account
    })
  })

  it('should list chart of accounts', () => {
    cy.apiRequest({
      method: 'GET',
      url: '/api/v1/accounting/accounts'
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data).to.be.an('array')
    })
  })

  it('should create a manual journal entry', () => {
    const today = new Date().toISOString().substring(0, 10)
    const referenceNumber = `JE-${Date.now()}`

    cy.apiCreateJournalEntry({
      referenceNumber,
      entryDate: today,
      memo: 'Cypress manual journal entry',
      lines: [
        {
          accountId: testAccounts.cash.id,
          description: 'Cash received',
          debit: 1000,
          credit: 0
        },
        {
          accountId: testAccounts.sales.id,
          description: 'Sales revenue',
          debit: 0,
          credit: 1000
        }
      ]
    }).then(entry => {
      testJournalEntry = entry
      expect(entry).to.have.property('id')
      expect(entry.referenceNumber).to.eq(referenceNumber)
      expect(entry.lines).to.have.length(2)
    })
  })

  it('should list journal entries including the new one', () => {
    cy.apiRequest({
      method: 'GET',
      url: '/api/v1/accounting/journal-entries'
    }).then(response => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data).to.be.an('array')

      const found = response.body.data.find(je => je.id === testJournalEntry.id)
      expect(found).to.exist
      expect(found.referenceNumber).to.eq(testJournalEntry.referenceNumber)
    })
  })

  it('should validate journal entry payload', () => {
    const today = new Date().toISOString().substring(0, 10)

    cy.apiRequest({
      method: 'POST',
      url: '/api/v1/accounting/journal-entries',
      body: {
        // Missing required lines array
        referenceNumber: `BAD-JE-${Date.now()}`,
        entryDate: today
      },
      failOnStatusCode: false
    }).then(response => {
      expect(response.status).to.be.oneOf([400, 422])
      expect(response.body.success).to.be.false
    })
  })
})

