describe('Order-to-Cash Complete Workflow', () => {
  let testDealer
  let testOrder
  let testAccounts = {}
  let traceId

  before(() => {
    // Setup test environment
    cy.apiLogin(Cypress.env('adminEmail'), Cypress.env('adminPassword'))
    cy.apiSwitchCompany(Cypress.env('companyCode'))
    
    // Create test accounts
    cy.apiCreateAccount({ code: 'TEST-CASH', name: 'Test Cash Account', type: 'ASSET' })
      .then(account => testAccounts.cash = account)
    
    cy.apiCreateAccount({ code: 'TEST-AR', name: 'Test Accounts Receivable', type: 'ASSET' })
      .then(account => testAccounts.receivable = account)
    
    cy.apiCreateAccount({ code: 'TEST-SALES', name: 'Test Sales Revenue', type: 'REVENUE' })
      .then(account => testAccounts.sales = account)
    
    cy.apiCreateAccount({ code: 'TEST-GST', name: 'Test GST Payable', type: 'LIABILITY' })
      .then(account => testAccounts.gst = account)
    
    // Create test dealer
    cy.apiCreateDealer({
      name: 'Order-to-Cash Test Dealer',
      code: 'O2C001',
      email: 'o2c@test.com',
      phone: '+91-9876543210',
      creditLimit: 500000
    }).then(dealer => testDealer = dealer)
    
    // Setup finished goods for inventory
    cy.apiRequest({
      method: 'POST',
      url: '/api/v1/inventory/finished-goods',
      body: {
        productCode: 'O2C-PAINT-001',
        name: 'Order-to-Cash Test Paint',
        unit: 'LITER',
        costingMethod: 'FIFO',
        valuationAccountId: testAccounts.receivable.id,
        cogsAccountId: testAccounts.sales.id,
        revenueAccountId: testAccounts.sales.id
      },
      failOnStatusCode: false
    }).then(response => {
      if (response.status === 200) {
        // Create inventory batch
        cy.apiRequest({
          method: 'POST',
          url: '/api/v1/inventory/finished-good-batches',
          body: {
            finishedGoodId: response.body.data.id,
            batchCode: 'BATCH-O2C-001',
            quantity: 100,
            unitCost: 800,
            manufacturedAt: new Date().toISOString()
          }
        })
      }
    })
  })

  it('Complete Order-to-Cash Flow: Order → Approval → Shipment → Invoice → Payment', () => {
    cy.log('**STEP 1: Create Sales Order**')
    cy.apiCreateOrder({
      dealerId: testDealer.id,
      items: [
        { productCode: 'O2C-PAINT-001', quantity: 10, unitPrice: 1000 }
      ],
      currency: 'INR',
      gstTreatment: 'ORDER_TOTAL',
      gstRate: 18.0,
      totalAmount: 11800, // 10000 + 18% GST
      notes: 'Order-to-Cash workflow test'
    }).then((order) => {
      testOrder = order
      expect(order.status).to.eq('BOOKED')
      expect(order.totalAmount).to.eq(11800)
      
      cy.log('**STEP 2: Auto-Approval Check**')
      // Wait a moment for auto-approval to potentially trigger
      cy.wait(2000)
      
      // Check if order was auto-approved
      cy.apiGetOrder(order.id).then((updatedOrder) => {
        if (updatedOrder.status === 'BOOKED') {
          cy.log('Order requires manual approval')
          
          cy.log('**STEP 3: Manual Order Approval**')
          cy.apiApproveOrder(order.id, 'Cypress Tester').then((approvalTraceId) => {
            traceId = approvalTraceId
            expect(traceId).to.exist
            
            // Verify order status changed
            cy.wait(1000) // Allow orchestration to complete
            cy.apiGetOrder(order.id).then((approvedOrder) => {
              expect(approvedOrder.status).to.be.oneOf(['PENDING_PRODUCTION', 'READY_TO_SHIP', 'RESERVED'])
            })
          })
        } else {
          cy.log('Order was auto-approved')
        }
        
        cy.log('**STEP 4: Mark Order as Shipped**')
        cy.apiRequest({
          method: 'POST',
          url: `/api/v1/orchestrator/orders/${order.id}/fulfillment`,
          body: {
            status: 'SHIPPED',
            notes: 'Shipped via Cypress test'
          }
        }).then((response) => {
          expect(response.status).to.eq(200)
          
          // Verify order is now shipped
          cy.wait(2000) // Allow orchestration to complete
          cy.apiGetOrder(order.id).then((shippedOrder) => {
            expect(shippedOrder.status).to.eq('SHIPPED')
          })
        })
        
        cy.log('**STEP 5: Verify Invoice Generated**')
        cy.apiRequest({
          method: 'GET',
          url: '/api/v1/invoices'
        }).then((response) => {
          expect(response.status).to.eq(200)
          const invoice = response.body.data.find(inv => 
            inv.salesOrderId === order.id
          )
          expect(invoice).to.exist
          expect(invoice.status).to.be.oneOf(['DRAFT', 'SENT'])
          expect(invoice.totalAmount).to.eq(11800)
        })
        
        cy.log('**STEP 6: Verify Accounting Entries Created**')
        cy.apiRequest({
          method: 'GET',
          url: '/api/v1/accounting/journal-entries'
        }).then((response) => {
          expect(response.status).to.eq(200)
          
          // Should have sales journal entry
          const salesJournal = response.body.data.find(je => 
            je.memo && je.memo.includes('Sales order')
          )
          expect(salesJournal).to.exist
          expect(salesJournal.status).to.eq('POSTED')
        })
        
        cy.log('**STEP 7: Verify Dealer Ledger Updated**')
        cy.apiRequest({
          method: 'GET',
          url: '/api/v1/sales/dealers'
        }).then((response) => {
          const dealer = response.body.data.find(d => d.id === testDealer.id)
          expect(dealer.outstandingBalance).to.be.greaterThan(0)
        })
        
        cy.log('**STEP 8: Record Customer Payment**')
        cy.apiRequest({
          method: 'POST',
          url: '/api/v1/accounting/receipts/dealer',
          body: {
            dealerId: testDealer.id,
            amount: 11800,
            cashAccountId: testAccounts.cash.id,
            receivableAccountId: testAccounts.receivable.id,
            reference: `PAYMENT-O2C-${Date.now()}`,
            notes: 'Test payment via Cypress'
          }
        }).then((response) => {
          expect(response.status).to.eq(200)
          expect(response.body.success).to.be.true
          
          cy.log('**STEP 9: Verify Payment Reflected in Dealer Balance**')
          cy.wait(1000)
          cy.apiRequest({
            method: 'GET',
            url: '/api/v1/sales/dealers'
          }).then((response) => {
            const dealer = response.body.data.find(d => d.id === testDealer.id)
            expect(dealer.outstandingBalance).to.eq(0)
          })
        })
        
        cy.log('**STEP 10: Verify Complete Workflow Trace**')
        if (traceId) {
          cy.apiRequest({
            method: 'GET',
            url: `/api/v1/orchestrator/trace/${traceId}`
          }).then((response) => {
            expect(response.status).to.eq(200)
            expect(response.body.data.events).to.be.an('array')
            expect(response.body.data.events.length).to.be.greaterThan(0)
          })
        }
      })
    })
  })

  it('Should handle inventory shortages correctly', () => {
    cy.log('**Testing Inventory Shortage Scenario**')
    
    // Create order for more than available stock
    cy.apiCreateOrder({
      dealerId: testDealer.id,
      items: [
        { productCode: 'O2C-PAINT-001', quantity: 200, unitPrice: 1000 } // More than 100 in stock
      ],
      currency: 'INR',
      gstTreatment: 'NONE',
      totalAmount: 200000,
      notes: 'Shortage test order'
    }).then((order) => {
      // Approve the order
      cy.apiApproveOrder(order.id, 'Shortage Tester').then(() => {
        cy.wait(2000)
        
        // Check if order is in pending production
        cy.apiGetOrder(order.id).then((updatedOrder) => {
          expect(updatedOrder.status).to.eq('PENDING_PRODUCTION')
        })
        
        // Verify production plan was created
        cy.apiRequest({
          method: 'GET',
          url: '/api/v1/factory/plans'
        }).then((response) => {
          const urgentPlan = response.body.data.find(plan => 
            plan.notes && plan.notes.includes('Urgent replenishment')
          )
          expect(urgentPlan).to.exist
        })
      })
    })
  })

  it('Should handle order cancellation correctly', () => {
    cy.log('**Testing Order Cancellation**')
    
    cy.apiCreateOrder({
      dealerId: testDealer.id,
      items: [
        { productCode: 'O2C-PAINT-001', quantity: 5, unitPrice: 1000 }
      ],
      notes: 'Order to be cancelled'
    }).then((order) => {
      // Cancel the order
      cy.apiRequest({
        method: 'POST',
        url: `/api/v1/sales/orders/${order.id}/cancel`,
        body: {
          reason: 'Cancelled for testing'
        }
      }).then((response) => {
        expect(response.status).to.eq(200)
        expect(response.body.data.status).to.eq('CANCELLED')
      })
    })
  })

  after(() => {
    cy.log('Cleaning up test data')
    // The cleanup would happen here, but for demo purposes, we'll leave data
  })
})
