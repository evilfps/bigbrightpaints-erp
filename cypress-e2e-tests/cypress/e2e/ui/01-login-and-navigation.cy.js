describe('Login and Navigation UI Tests', () => {
  beforeEach(() => {
    cy.visit('/')
  })

  it('should display login page', () => {
    cy.url().should('include', '/login')
    cy.get('[data-cy=login-form]').should('be.visible')
    cy.get('[data-cy=email-input]').should('be.visible')
    cy.get('[data-cy=password-input]').should('be.visible')
    cy.get('[data-cy=login-button]').should('be.visible')
    cy.get('[data-cy=app-title]').should('contain', 'BigBright ERP')
  })

  it('should show validation errors for empty form', () => {
    cy.get('[data-cy=login-button]').click()
    cy.get('[data-cy=email-error]').should('contain', 'Email is required')
    cy.get('[data-cy=password-error]').should('contain', 'Password is required')
  })

  it('should show error for invalid credentials', () => {
    cy.get('[data-cy=email-input]').type('invalid@example.com')
    cy.get('[data-cy=password-input]').type('wrongpassword')
    cy.get('[data-cy=login-button]').click()
    
    cy.get('[data-cy=error-message]').should('be.visible')
    cy.get('[data-cy=error-message]').should('contain', 'Invalid credentials')
  })

  it('should login successfully with valid credentials', () => {
    cy.loginAsAdmin()
    
    cy.url().should('not.include', '/login')
    cy.get('[data-cy=dashboard]').should('be.visible')
    cy.get('[data-cy=user-menu]').should('be.visible')
    cy.get('[data-cy=current-user]').should('contain', 'admin@bbp.dev')
  })

  it('should navigate between modules', () => {
    cy.loginAsAdmin()
    
    // Test Sales module navigation
    cy.navigateToModule('sales')
    cy.get('[data-cy=sales-dashboard]').should('be.visible')
    cy.get('[data-cy=nav-sales]').should('have.class', 'active')
    
    // Test Inventory module navigation
    cy.navigateToModule('inventory')
    cy.get('[data-cy=inventory-dashboard]').should('be.visible')
    cy.get('[data-cy=nav-inventory]').should('have.class', 'active')
    
    // Test Accounting module navigation
    cy.navigateToModule('accounting')
    cy.get('[data-cy=accounting-dashboard]').should('be.visible')
    cy.get('[data-cy=nav-accounting]').should('have.class', 'active')
  })

  it('should display correct user permissions', () => {
    cy.loginAsAdmin()
    
    // Admin should see all modules
    cy.get('[data-cy=nav-sales]').should('be.visible')
    cy.get('[data-cy=nav-inventory]').should('be.visible')
    cy.get('[data-cy=nav-accounting]').should('be.visible')
    cy.get('[data-cy=nav-factory]').should('be.visible')
    cy.get('[data-cy=nav-hr]').should('be.visible')
    cy.get('[data-cy=nav-reports]').should('be.visible')
  })

  it('should show company context correctly', () => {
    cy.loginAsAdmin()
    
    cy.get('[data-cy=current-company]').should('contain', 'BBP')
    cy.get('[data-cy=company-switcher]').should('be.visible')
  })

  it('should handle company switching', () => {
    cy.loginAsAdmin()
    
    cy.get('[data-cy=company-switcher]').click()
    cy.get('[data-cy=company-list]').should('be.visible')
    
    // If there are multiple companies, test switching
    cy.get('[data-cy=company-option]').then($options => {
      if ($options.length > 1) {
        cy.get('[data-cy=company-option]').first().click()
        cy.get('[data-cy=success-message]').should('contain', 'Company switched')
      }
    })
  })

  it('should logout successfully', () => {
    cy.loginAsAdmin()
    cy.logout()
    
    cy.url().should('include', '/login')
    cy.get('[data-cy=login-form]').should('be.visible')
  })

  describe('Responsive Design Tests', () => {
    it('should work on mobile viewport', () => {
      cy.viewport(375, 667) // iPhone SE
      cy.loginAsAdmin()
      
      cy.get('[data-cy=mobile-menu-toggle]').should('be.visible')
      cy.get('[data-cy=mobile-menu-toggle]').click()
      cy.get('[data-cy=mobile-nav]').should('be.visible')
    })

    it('should work on tablet viewport', () => {
      cy.viewport(768, 1024) // iPad
      cy.loginAsAdmin()
      
      cy.get('[data-cy=nav-sidebar]').should('be.visible')
      cy.navigateToModule('sales')
      cy.get('[data-cy=sales-dashboard]').should('be.visible')
    })
  })

  describe('Accessibility Tests', () => {
    it('should have proper ARIA labels', () => {
      cy.get('[data-cy=email-input]').should('have.attr', 'aria-label')
      cy.get('[data-cy=password-input]').should('have.attr', 'aria-label')
      cy.get('[data-cy=login-button]').should('have.attr', 'aria-label')
    })

    it('should support keyboard navigation', () => {
      cy.get('[data-cy=email-input]').focus()
      cy.get('[data-cy=email-input]').tab()
      cy.focused().should('have.attr', 'data-cy', 'password-input')
      
      cy.focused().tab()
      cy.focused().should('have.attr', 'data-cy', 'login-button')
    })

    it('should have proper heading structure', () => {
      cy.loginAsAdmin()
      
      cy.get('h1').should('exist')
      cy.get('h1').should('be.visible')
    })
  })
})
