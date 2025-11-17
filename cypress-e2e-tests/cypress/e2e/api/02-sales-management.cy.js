describe('Sales Management API Tests', () => {
  let testDealer
  let testOrder

  before(() => {
    cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
    cy.apiSwitchCompany(Cypress.env('companyCode'))
  })

  describe('Dealer Management', () => {
    it('should create a new dealer', () => {
      cy.apiCreateDealer({
        name: 'Cypress Test Dealer',
        code: 'CYP001',
        email: 'cypress.dealer@test.com',
        phone: '+91-9876543210',
        creditLimit: 250000
      }).then((dealer) => {
        testDealer = dealer
        expect(dealer).to.have.property('id')
        expect(dealer.name).to.eq('Cypress Test Dealer')
        expect(dealer.code).to.eq('CYP001')
        expect(dealer.creditLimit).to.eq(250000)
        expect(dealer.status).to.eq('ACTIVE')
      })
    })

    it('should list all dealers', () => {
      cy.apiRequest({
        method: 'GET',
        url: '/api/v1/sales/dealers'
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.success).to.be.true
        expect(response.body.data).to.be.an('array')
        
        const dealer = response.body.data.find(d => d.code === 'CYP001')
        expect(dealer).to.exist
        expect(dealer.outstandingBalance).to.eq(0)
      })
    })

    it('should update dealer information', () => {
      cy.apiRequest({
        method: 'PUT',
        url: `/api/v1/sales/dealers/${testDealer.id}`,
        body: {
          name: 'Updated Cypress Dealer',
          code: 'CYP001',
          email: 'updated.dealer@test.com',
          phone: '+91-9999888877',
          creditLimit: 300000
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data.name).to.eq('Updated Cypress Dealer')
        expect(response.body.data.creditLimit).to.eq(300000)
      })
    })
  })

  describe('Sales Order Management', () => {
    it('should create a sales order', () => {
      cy.apiCreateOrder({
        dealerId: testDealer.id,
        items: [
          { productCode: 'PAINT-WHITE-001', quantity: 5, unitPrice: 1200 },
          { productCode: 'PAINT-BLUE-002', quantity: 3, unitPrice: 1500 }
        ],
        currency: 'INR',
        gstTreatment: 'ORDER_TOTAL',
        gstRate: 18.0,
        notes: 'Cypress test order with GST'
      }).then((order) => {
        testOrder = order
        expect(order).to.have.property('id')
        expect(order.status).to.eq('BOOKED')
        expect(order.items).to.have.length(2)
        expect(order.gstTreatment).to.eq('ORDER_TOTAL')
        expect(order.gstRate).to.eq(18.0)
        expect(order.totalAmount).to.be.greaterThan(0)
      })
    })

    it('should list sales orders', () => {
      cy.apiRequest({
        method: 'GET',
        url: '/api/v1/sales/orders'
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data).to.be.an('array')
        
        const order = response.body.data.find(o => o.id === testOrder.id)
        expect(order).to.exist
        expect(order.items).to.have.length(2)
      })
    })

    it('should filter orders by status', () => {
      cy.apiRequest({
        method: 'GET',
        url: '/api/v1/sales/orders?status=BOOKED'
      }).then((response) => {
        expect(response.status).to.eq(200)
        response.body.data.forEach(order => {
          expect(order.status).to.eq('BOOKED')
        })
      })
    })

    it('should confirm an order', () => {
      cy.apiRequest({
        method: 'POST',
        url: `/api/v1/sales/orders/${testOrder.id}/confirm`
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data.status).to.eq('CONFIRMED')
      })
    })

    it('should update order status', () => {
      cy.apiRequest({
        method: 'PATCH',
        url: `/api/v1/sales/orders/${testOrder.id}/status`,
        body: { status: 'PROCESSING' }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data.status).to.eq('PROCESSING')
      })
    })

    it('should calculate GST correctly for ORDER_TOTAL treatment', () => {
      cy.apiCreateOrder({
        dealerId: testDealer.id,
        items: [
          { productCode: 'PAINT-RED-003', quantity: 10, unitPrice: 1000 }
        ],
        gstTreatment: 'ORDER_TOTAL',
        gstRate: 18.0
      }).then((order) => {
        const expectedSubtotal = 10000 // 10 * 1000
        const expectedGst = expectedSubtotal * 0.18 // 1800
        const expectedTotal = expectedSubtotal + expectedGst // 11800
        
        expect(order.subtotalAmount).to.eq(expectedSubtotal)
        expect(order.gstTotal).to.eq(expectedGst)
        expect(order.totalAmount).to.eq(expectedTotal)
      })
    })
  })

  describe('Promotions', () => {
    it('should create a promotion', () => {
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/sales/promotions',
        body: {
          name: 'Cypress Test Promotion',
          description: 'Test promotion for Cypress',
          discountType: 'PERCENTAGE',
          discountValue: 10.0,
          startDate: '2024-01-01',
          endDate: '2024-12-31'
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data.name).to.eq('Cypress Test Promotion')
        expect(response.body.data.discountType).to.eq('PERCENTAGE')
        expect(response.body.data.discountValue).to.eq(10.0)
      })
    })

    it('should list promotions', () => {
      cy.apiRequest({
        method: 'GET',
        url: '/api/v1/sales/promotions'
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data).to.be.an('array')
      })
    })
  })

  describe('Sales Targets', () => {
    it('should create a sales target', () => {
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/sales/targets',
        body: {
          name: 'Q1 2024 Target',
          periodStart: '2024-01-01',
          periodEnd: '2024-03-31',
          targetAmount: 500000,
          assignee: 'Cypress Test User'
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data.name).to.eq('Q1 2024 Target')
        expect(response.body.data.targetAmount).to.eq(500000)
        expect(response.body.data.achievedAmount).to.eq(0)
      })
    })
  })

  describe('Credit Requests', () => {
    it('should create a credit request', () => {
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/sales/credit-requests',
        body: {
          dealerId: testDealer.id,
          amountRequested: 50000,
          reason: 'Cypress test credit request'
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data.amountRequested).to.eq(50000)
        expect(response.body.data.status).to.eq('PENDING')
      })
    })
  })

  describe('Order Validation', () => {
    it('should reject order with invalid dealer ID', () => {
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/sales/orders',
        body: {
          dealerId: 99999,
          items: [{ productCode: 'PAINT-001', quantity: 1, unitPrice: 100 }]
        },
        failOnStatusCode: false
      }).then((response) => {
        expect(response.status).to.be.oneOf([400, 404])
        expect(response.body.success).to.be.false
      })
    })

    it('should reject order with empty items', () => {
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/sales/orders',
        body: {
          dealerId: testDealer.id,
          items: []
        },
        failOnStatusCode: false
      }).then((response) => {
        expect(response.status).to.eq(400)
        expect(response.body.success).to.be.false
      })
    })

    it('should reject order with negative quantity', () => {
      cy.apiRequest({
        method: 'POST',
        url: '/api/v1/sales/orders',
        body: {
          dealerId: testDealer.id,
          items: [{ productCode: 'PAINT-001', quantity: -5, unitPrice: 100 }]
        },
        failOnStatusCode: false
      }).then((response) => {
        expect(response.status).to.eq(400)
        expect(response.body.success).to.be.false
      })
    })
  })

  after(() => {
    // Cleanup test data
    if (testDealer) {
      cy.apiRequest({
        method: 'DELETE',
        url: `/api/v1/sales/dealers/${testDealer.id}`,
        failOnStatusCode: false
      })
    }
  })
})
