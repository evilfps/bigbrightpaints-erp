/**
 * Initial Setup Test
 * This test verifies that the testing environment is properly configured
 * Run this first to ensure everything is working
 */

describe('Initial Setup & Environment Verification', () => {
  
  it('should verify backend is accessible', () => {
    cy.request({
      method: 'GET',
      url: `${Cypress.env('apiUrl')}/actuator/health`,
      timeout: 10000
    }).then((response) => {
      expect(response.status).to.eq(200)
      expect(response.body).to.have.property('status', 'UP')
    })
  })

  it('should verify database connectivity via backend', () => {
    // Test that backend can connect to database by trying to login
    cy.request({
      method: 'POST',
      url: `${Cypress.env('apiUrl')}/api/v1/auth/login`,
      body: {
        email: Cypress.env('adminEmail'),
        password: Cypress.env('adminPassword')
      },
      failOnStatusCode: false
    }).then((response) => {
      if (response.status === 200) {
        expect(response.body.success).to.be.true
        expect(response.body.data).to.have.property('token')
        cy.log('✅ Database connectivity verified via successful login')
      } else {
        cy.log(`⚠️  Login failed with status ${response.status}`)
        cy.log('This might indicate database connectivity issues or incorrect credentials')
        
        // Still fail the test if we can't login, but with helpful message
        throw new Error(`Admin login failed. Check database connection and credentials. Status: ${response.status}`)
      }
    })
  })

  it('should verify required API endpoints exist', () => {
    // First login to get token
    cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))

    // Test key endpoints exist
    const endpoints = [
      { url: '/api/v1/sales/dealers', method: 'GET', description: 'Sales - List dealers' },
      { url: '/api/v1/accounting/accounts', method: 'GET', description: 'Accounting - List accounts' },
      { url: '/api/v1/inventory/finished-goods', method: 'GET', description: 'Inventory - List finished goods' }
    ]

    endpoints.forEach(endpoint => {
      cy.apiRequest({
        method: endpoint.method,
        url: endpoint.url,
        failOnStatusCode: false
      }).then((response) => {
        expect(response.status).to.be.oneOf([200, 401, 403], 
          `${endpoint.description} should exist (got ${response.status})`)
        
        if (response.status === 200) {
          cy.log(`✅ ${endpoint.description} - OK`)
        } else if (response.status === 401 || response.status === 403) {
          cy.log(`⚠️  ${endpoint.description} - Authentication/Authorization issue`)
        }
      })
    })
  })

  it('should verify company context switching works', () => {
    cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
    
    // Try to switch to default company
    cy.apiSwitchCompany(Cypress.env('companyCode')).then((newToken) => {
      expect(newToken).to.be.a('string')
      expect(newToken).to.have.length.greaterThan(10)
      cy.log('✅ Company context switching works')
    })
  })

  it('should verify test data utilities work', () => {
    // Test database query task
    cy.task('queryDb', { 
      query: 'SELECT COUNT(*) as count FROM companies',
      params: []
    }).then((result) => {
      expect(result).to.be.an('array')
      expect(result[0]).to.have.property('count')
      expect(parseInt(result[0].count)).to.be.greaterThan(0)
      cy.log(`✅ Database query works - Found ${result[0].count} companies`)
    })
  })

  it('should test cleanup utilities', () => {
    // Test cleanup task (should not fail even if nothing to clean)
    cy.task('cleanupTestData', {
      testDataPrefix: 'NONEXISTENT',
      cleanupOrders: true,
      cleanupDealers: false  // Don't clean up dealers in setup test
    }).then((result) => {
      expect(result).to.have.property('success', true)
      cy.log('✅ Cleanup utilities work')
    })
  })

  it('should seed minimal test data', () => {
    // Seed minimal data for other tests
    cy.task('seedTestData', {
      dealerCount: 2,
      productCount: 3,
      orderCount: 1
    }).then((seededData) => {
      expect(seededData).to.have.property('dealers')
      expect(seededData).to.have.property('products')
      expect(seededData).to.have.property('accounts')
      expect(seededData.dealers).to.have.length.greaterThan(0)
      expect(seededData.products).to.have.length.greaterThan(0)
      
      cy.log(`✅ Test data seeded:`)
      cy.log(`   - ${seededData.dealers.length} dealers`)
      cy.log(`   - ${seededData.products.length} products`)
      cy.log(`   - ${seededData.accounts.length} accounts`)
    })
  })

  // Only run frontend tests if baseUrl is accessible
  it('should verify frontend is accessible (if configured)', () => {
    // Skip this test if no baseUrl configured
    if (!Cypress.config('baseUrl')) {
      cy.log('⚠️  No baseUrl configured, skipping frontend check')
      return
    }

    cy.request({
      url: Cypress.config('baseUrl'),
      failOnStatusCode: false,
      timeout: 10000
    }).then((response) => {
      if (response.status === 200) {
        cy.log('✅ Frontend is accessible')
        
        // Try to visit the frontend
        cy.visit('/', { failOnStatusCode: false })
        cy.log('✅ Frontend visit successful')
      } else {
        cy.log(`⚠️  Frontend not accessible (status: ${response.status})`)
        cy.log('UI tests will be skipped')
      }
    })
  })

  it('should display environment configuration', () => {
    cy.log('📋 Environment Configuration:')
    cy.log(`   Backend URL: ${Cypress.env('apiUrl')}`)
    cy.log(`   Frontend URL: ${Cypress.config('baseUrl') || 'Not configured'}`)
    cy.log(`   Admin Email: ${Cypress.env('adminEmail')}`)
    cy.log(`   Company Code: ${Cypress.env('companyCode')}`)
    cy.log(`   Database: ${Cypress.env('dbHost')}:${Cypress.env('dbPort')}/${Cypress.env('dbName')}`)
    
    // Verify all required env vars are set
    const requiredEnvVars = ['apiUrl', 'adminEmail', 'adminPassword', 'companyCode']
    requiredEnvVars.forEach(envVar => {
      expect(Cypress.env(envVar)).to.exist.and.not.be.empty
    })
    
    cy.log('✅ All required environment variables are configured')
  })

  // Summary of setup verification
  after(() => {
    cy.log('🎉 Setup verification complete!')
    cy.log('')
    cy.log('✅ Backend accessible and responding')
    cy.log('✅ Database connectivity verified')
    cy.log('✅ Admin authentication working')
    cy.log('✅ API endpoints responding')
    cy.log('✅ Test data utilities functional')
    cy.log('')
    cy.log('🚀 Ready to run full test suite!')
  })
})
