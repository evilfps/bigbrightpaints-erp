describe('Authentication API Tests', () => {
  const testUser = {
    email: 'test@example.com',
    password: 'TestPassword123!',
    displayName: 'Test User'
  }

  beforeEach(() => {
    cy.task('cleanupTestData', { email: testUser.email })
  })

  it('should register a new user', () => {
    cy.request({
      method: 'POST',
      url: `${Cypress.env('apiUrl')}/api/v1/auth/register`,
      body: {
        email: testUser.email,
        password: testUser.password,
        displayName: testUser.displayName
      }
    }).then((response) => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data).to.have.property('id')
      expect(response.body.data.email).to.eq(testUser.email)
      expect(response.body.data.displayName).to.eq(testUser.displayName)
    })
  })

  it('should login with valid credentials', () => {
    // First register the user
    cy.request('POST', `${Cypress.env('apiUrl')}/api/v1/auth/register`, testUser)

    // Then login
    cy.request({
      method: 'POST',
      url: `${Cypress.env('apiUrl')}/api/v1/auth/login`,
      body: {
        email: testUser.email,
        password: testUser.password
      }
    }).then((response) => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data).to.have.property('token')
      expect(response.body.data).to.have.property('refreshToken')
      expect(response.body.data.user.email).to.eq(testUser.email)
    })
  })

  it('should reject login with invalid credentials', () => {
    cy.request({
      method: 'POST',
      url: `${Cypress.env('apiUrl')}/api/v1/auth/login`,
      body: {
        email: 'nonexistent@example.com',
        password: 'wrongpassword'
      },
      failOnStatusCode: false
    }).then((response) => {
      expect(response.status).to.eq(401)
      expect(response.body.success).to.be.false
    })
  })

  it('should refresh token with valid refresh token', () => {
    // Register and login first
    cy.request('POST', `${Cypress.env('apiUrl')}/api/v1/auth/register`, testUser)
    
    cy.request('POST', `${Cypress.env('apiUrl')}/api/v1/auth/login`, {
      email: testUser.email,
      password: testUser.password
    }).then((loginResponse) => {
      const refreshToken = loginResponse.body.data.refreshToken

      // Use refresh token
      cy.request({
        method: 'POST',
        url: `${Cypress.env('apiUrl')}/api/v1/auth/refresh-token`,
        body: {
          refreshToken
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.success).to.be.true
        expect(response.body.data).to.have.property('token')
        expect(response.body.data).to.have.property('refreshToken')
      })
    })
  })

  it('should get user profile with valid token', () => {
    cy.request('POST', `${Cypress.env('apiUrl')}/api/v1/auth/register`, testUser)
    cy.apiLogin(testUser.email, testUser.password)

    cy.apiRequest({
      method: 'GET',
      url: '/api/v1/auth/profile'
    }).then((response) => {
      expect(response.status).to.eq(200)
      expect(response.body.success).to.be.true
      expect(response.body.data.email).to.eq(testUser.email)
    })
  })

  describe('MFA Tests', () => {
    beforeEach(() => {
      cy.request('POST', `${Cypress.env('apiUrl')}/api/v1/auth/register`, testUser)
      cy.apiLogin(testUser.email, testUser.password)
    })

    it('should setup MFA for user', () => {
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/auth/mfa/setup'
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.success).to.be.true
        expect(response.body.data).to.have.property('qrCodeUri')
        expect(response.body.data).to.have.property('recoveryCodes')
        expect(response.body.data.recoveryCodes).to.have.length(10)
      })
    })

    it('should activate MFA with master code', () => {
      // Setup MFA first
      cy.apiRequest({ method: 'POST', url: '/api/v1/auth/mfa/setup' })

      // Activate with master code (from config)
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/auth/mfa/activate',
        body: {
          totpCode: Cypress.env('masterMfaCode') || '000000'
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.success).to.be.true
      })
    })

    it('should disable MFA with master code', () => {
      // Setup and activate MFA
      cy.apiRequest({ method: 'POST', url: '/api/v1/auth/mfa/setup' })
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/auth/mfa/activate',
        body: { totpCode: '000000' }
      })

      // Disable MFA
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/auth/mfa/disable',
        body: {
          totpCode: '000000'
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.success).to.be.true
      })
    })
  })
})
