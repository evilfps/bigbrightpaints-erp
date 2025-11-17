describe('Dealer Management UI Tests', () => {
  beforeEach(() => {
    cy.loginAsAdmin()
    cy.navigateToModule('sales')
    cy.get('[data-cy=dealers-tab]').click()
  })

  it('should display dealers list', () => {
    cy.get('[data-cy=dealers-table]').should('be.visible')
    cy.get('[data-cy=add-dealer-button]').should('be.visible')
    cy.get('[data-cy=dealers-search]').should('be.visible')
  })

  it('should create a new dealer', () => {
    const dealerData = {
      name: 'UI Test Dealer',
      code: `UI-${Date.now()}`,
      email: 'ui-test@dealer.com',
      phone: '+91-9876543210',
      creditLimit: '150000'
    }

    cy.createTestDealer(dealerData).then((dealer) => {
      // Verify dealer appears in table
      cy.get('[data-cy=dealers-table]').should('contain', dealer.name)
      cy.get('[data-cy=dealers-table]').should('contain', dealer.code)
      cy.get('[data-cy=dealers-table]').should('contain', dealer.email)
    })
  })

  it('should validate dealer form', () => {
    cy.get('[data-cy=add-dealer-button]').click()
    cy.get('[data-cy=dealer-form]').should('be.visible')
    
    // Try to save without required fields
    cy.get('[data-cy=save-dealer-button]').click()
    
    cy.get('[data-cy=name-error]').should('contain', 'Name is required')
    cy.get('[data-cy=code-error]').should('contain', 'Code is required')
  })

  it('should search dealers', () => {
    // First create a dealer to search for
    cy.createTestDealer({ name: 'Searchable Dealer', code: 'SEARCH001' })
    
    // Search for the dealer
    cy.get('[data-cy=dealers-search]').type('Searchable')
    cy.get('[data-cy=dealers-table]').should('contain', 'Searchable Dealer')
    
    // Clear search
    cy.get('[data-cy=dealers-search]').clear()
    cy.get('[data-cy=dealers-table]').should('be.visible')
  })

  it('should edit dealer information', () => {
    // Create dealer first
    cy.createTestDealer({ name: 'Edit Test Dealer', code: 'EDIT001' })
    
    // Find and edit the dealer
    cy.get('[data-cy=dealers-table]')
      .contains('Edit Test Dealer')
      .parents('tr')
      .find('[data-cy=edit-dealer-button]')
      .click()
    
    cy.get('[data-cy=dealer-form]').should('be.visible')
    cy.get('[data-cy=dealer-name-input]').clear().type('Updated Dealer Name')
    cy.get('[data-cy=dealer-credit-limit-input]').clear().type('200000')
    
    cy.get('[data-cy=save-dealer-button]').click()
    cy.checkSuccessMessage('Dealer updated successfully')
    
    // Verify changes
    cy.get('[data-cy=dealers-table]').should('contain', 'Updated Dealer Name')
  })

  it('should display dealer details', () => {
    cy.createTestDealer({ name: 'Detail Test Dealer', code: 'DETAIL001' })
    
    cy.get('[data-cy=dealers-table]')
      .contains('Detail Test Dealer')
      .parents('tr')
      .find('[data-cy=view-dealer-button]')
      .click()
    
    cy.get('[data-cy=dealer-details-modal]').should('be.visible')
    cy.get('[data-cy=dealer-name]').should('contain', 'Detail Test Dealer')
    cy.get('[data-cy=dealer-code]').should('contain', 'DETAIL001')
    cy.get('[data-cy=dealer-status]').should('contain', 'ACTIVE')
    cy.get('[data-cy=dealer-outstanding-balance]').should('contain', '₹0.00')
  })

  it('should handle dealer credit limit warnings', () => {
    cy.createTestDealer({ 
      name: 'Credit Limit Dealer', 
      code: 'CREDIT001',
      creditLimit: '50000'
    })
    
    // Navigate to create order for this dealer
    cy.get('[data-cy=orders-tab]').click()
    cy.get('[data-cy=add-order-button]').click()
    
    // Select the dealer
    cy.get('[data-cy=dealer-select]').select('Credit Limit Dealer')
    
    // Add items that exceed credit limit
    cy.get('[data-cy=item-product-0]').type('PAINT-001')
    cy.get('[data-cy=item-quantity-0]').type('100')
    cy.get('[data-cy=item-price-0]').type('1000')
    
    // Should show warning about credit limit
    cy.get('[data-cy=credit-limit-warning]').should('be.visible')
    cy.get('[data-cy=credit-limit-warning]').should('contain', 'exceeds credit limit')
  })

  it('should filter dealers by status', () => {
    // Create dealers with different statuses
    cy.createTestDealer({ name: 'Active Dealer', code: 'ACTIVE001' })
    
    // Filter by active status
    cy.get('[data-cy=status-filter]').select('ACTIVE')
    cy.get('[data-cy=dealers-table]').should('contain', 'Active Dealer')
    
    // Filter by all
    cy.get('[data-cy=status-filter]').select('ALL')
    cy.get('[data-cy=dealers-table]').should('contain', 'Active Dealer')
  })

  it('should export dealers list', () => {
    cy.get('[data-cy=export-dealers-button]').click()
    cy.get('[data-cy=export-format-select]').select('CSV')
    cy.get('[data-cy=confirm-export-button]').click()
    
    // Should trigger download
    cy.get('[data-cy=success-message]').should('contain', 'Export initiated')
  })

  it('should handle pagination', () => {
    // This test assumes there are enough dealers to paginate
    cy.get('[data-cy=dealers-table]').should('be.visible')
    
    cy.get('[data-cy=pagination]').then($pagination => {
      if ($pagination.find('[data-cy=next-page]').length > 0) {
        cy.get('[data-cy=next-page]').click()
        cy.get('[data-cy=current-page]').should('contain', '2')
        
        cy.get('[data-cy=previous-page]').click()
        cy.get('[data-cy=current-page]').should('contain', '1')
      }
    })
  })

  describe('Dealer Ledger Tests', () => {
    it('should view dealer ledger', () => {
      cy.createTestDealer({ name: 'Ledger Test Dealer', code: 'LEDGER001' })
      
      cy.get('[data-cy=dealers-table]')
        .contains('Ledger Test Dealer')
        .parents('tr')
        .find('[data-cy=view-ledger-button]')
        .click()
      
      cy.get('[data-cy=dealer-ledger-modal]').should('be.visible')
      cy.get('[data-cy=ledger-balance]').should('be.visible')
      cy.get('[data-cy=ledger-entries-table]').should('be.visible')
    })

    it('should record dealer payment', () => {
      cy.createTestDealer({ name: 'Payment Test Dealer', code: 'PAY001' })
      
      // First create an invoice/order to have outstanding balance
      // Then record payment
      cy.get('[data-cy=dealers-table]')
        .contains('Payment Test Dealer')
        .parents('tr')
        .find('[data-cy=record-payment-button]')
        .click()
      
      cy.get('[data-cy=payment-form]').should('be.visible')
      cy.get('[data-cy=payment-amount]').type('5000')
      cy.get('[data-cy=payment-reference]').type('TEST-PAY-001')
      cy.get('[data-cy=payment-notes]').type('Test payment')
      
      cy.get('[data-cy=record-payment-button]').click()
      cy.checkSuccessMessage('Payment recorded successfully')
    })
  })

  describe('Error Handling', () => {
    it('should handle network errors gracefully', () => {
      // Intercept API calls and make them fail
      cy.intercept('GET', '/api/v1/sales/dealers', { forceNetworkError: true }).as('getDealers')
      
      cy.reload()
      cy.navigateToModule('sales')
      cy.get('[data-cy=dealers-tab]').click()
      
      cy.get('[data-cy=error-message]').should('be.visible')
      cy.get('[data-cy=retry-button]').should('be.visible')
      
      // Test retry functionality
      cy.intercept('GET', '/api/v1/sales/dealers', { fixture: 'dealers.json' }).as('getDealersRetry')
      cy.get('[data-cy=retry-button]').click()
      cy.waitForApi('@getDealersRetry')
      
      cy.get('[data-cy=dealers-table]').should('be.visible')
    })

    it('should handle validation errors from server', () => {
      cy.intercept('POST', '/api/v1/sales/dealers', {
        statusCode: 400,
        body: {
          success: false,
          message: 'Validation failed',
          errors: {
            code: 'Dealer code already exists'
          }
        }
      }).as('createDealerError')
      
      cy.get('[data-cy=add-dealer-button]').click()
      cy.get('[data-cy=dealer-name-input]').type('Error Test Dealer')
      cy.get('[data-cy=dealer-code-input]').type('ERROR001')
      cy.get('[data-cy=dealer-email-input]').type('error@test.com')
      
      cy.get('[data-cy=save-dealer-button]').click()
      cy.waitForApi('@createDealerError')
      
      cy.get('[data-cy=code-error]').should('contain', 'already exists')
    })
  })
})
