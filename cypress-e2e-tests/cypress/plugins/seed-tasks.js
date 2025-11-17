const { faker } = require('@faker-js/faker')
const { Client } = require('pg')

const dbConfig = {
  host: process.env.DB_HOST || 'localhost',
  port: process.env.DB_PORT || 5432,
  database: process.env.DB_NAME || 'erp_domain',
  user: process.env.DB_USER || 'erp',
  password: process.env.DB_PASSWORD || 'erp'
}

async function seedTestData(options = {}) {
  const client = new Client(dbConfig)
  
  try {
    await client.connect()
    await client.query('BEGIN')
    
    // Get the default company ID (assuming BBP exists)
    const companyResult = await client.query(
      "SELECT id FROM companies WHERE code = 'BBP' LIMIT 1"
    )
    
    if (companyResult.rows.length === 0) {
      throw new Error('Default company BBP not found')
    }
    
    const companyId = companyResult.rows[0].id
    
    const seededData = {
      companyId,
      dealers: [],
      products: [],
      accounts: [],
      orders: []
    }
    
    // Create test accounts
    const accountTypes = ['ASSET', 'REVENUE', 'EXPENSE', 'LIABILITY']
    for (const type of accountTypes) {
      const account = await client.query(`
        INSERT INTO accounts (company_id, code, name, type, balance)
        VALUES ($1, $2, $3, $4, $5)
        ON CONFLICT (company_id, code) DO NOTHING
        RETURNING *
      `, [
        companyId,
        `TEST-${type}-${faker.string.alphanumeric(4).toUpperCase()}`,
        `Test ${type} Account`,
        type,
        faker.finance.amount({ min: 0, max: 100000, dec: 2 })
      ])
      
      if (account.rows.length > 0) {
        seededData.accounts.push(account.rows[0])
      }
    }
    
    // Create test dealers
    const dealerCount = options.dealerCount || 5
    for (let i = 0; i < dealerCount; i++) {
      const dealerCode = `TEST-D${i.toString().padStart(3, '0')}`
      
      // Create receivable account for dealer
      const receivableAccount = await client.query(`
        INSERT INTO accounts (company_id, code, name, type, balance)
        VALUES ($1, $2, $3, 'ASSET', 0)
        ON CONFLICT (company_id, code) DO NOTHING
        RETURNING id
      `, [
        companyId,
        `AR-${dealerCode}`,
        `${dealerCode} Receivable`
      ])
      
      const dealer = await client.query(`
        INSERT INTO dealers (
          company_id, name, code, email, phone, status, 
          credit_limit, outstanding_balance, receivable_account_id
        )
        VALUES ($1, $2, $3, $4, $5, 'ACTIVE', $6, 0, $7)
        ON CONFLICT (company_id, code) DO NOTHING
        RETURNING *
      `, [
        companyId,
        faker.company.name(),
        dealerCode,
        faker.internet.email(),
        faker.phone.number(),
        faker.finance.amount({ min: 50000, max: 500000, dec: 0 }),
        receivableAccount.rows[0]?.id
      ])
      
      if (dealer.rows.length > 0) {
        seededData.dealers.push(dealer.rows[0])
      }
    }
    
    // Create test finished goods
    const productCount = options.productCount || 10
    for (let i = 0; i < productCount; i++) {
      const productCode = `TEST-P${i.toString().padStart(3, '0')}`
      
      const product = await client.query(`
        INSERT INTO finished_goods (
          company_id, product_code, name, unit, costing_method,
          current_stock, reserved_stock
        )
        VALUES ($1, $2, $3, 'LITER', 'FIFO', $4, 0)
        ON CONFLICT (company_id, product_code) DO NOTHING
        RETURNING *
      `, [
        companyId,
        productCode,
        `Test Paint ${productCode}`,
        faker.number.int({ min: 50, max: 500 })
      ])
      
      if (product.rows.length > 0) {
        seededData.products.push(product.rows[0])
        
        // Create inventory batches for the product
        const batchCount = faker.number.int({ min: 1, max: 3 })
        for (let j = 0; j < batchCount; j++) {
          await client.query(`
            INSERT INTO finished_good_batches (
              finished_good_id, batch_code, quantity_total, 
              quantity_available, unit_cost, manufactured_at
            )
            VALUES ($1, $2, $3, $3, $4, $5)
          `, [
            product.rows[0].id,
            `${productCode}-B${j + 1}`,
            faker.number.int({ min: 10, max: 100 }),
            faker.finance.amount({ min: 500, max: 1500, dec: 2 }),
            faker.date.recent({ days: 30 })
          ])
        }
      }
    }
    
    // Create test sales orders
    const orderCount = options.orderCount || 8
    for (let i = 0; i < orderCount; i++) {
      const orderNumber = `TEST-SO-${Date.now()}-${i}`
      const dealer = faker.helpers.arrayElement(seededData.dealers)
      const status = faker.helpers.arrayElement([
        'BOOKED', 'CONFIRMED', 'RESERVED', 'READY_TO_SHIP', 'SHIPPED'
      ])
      
      const order = await client.query(`
        INSERT INTO sales_orders (
          company_id, dealer_id, order_number, status, 
          total_amount, subtotal_amount, currency, notes
        )
        VALUES ($1, $2, $3, $4, $5, $6, 'INR', $7)
        RETURNING *
      `, [
        companyId,
        dealer.id,
        orderNumber,
        status,
        faker.finance.amount({ min: 5000, max: 50000, dec: 2 }),
        faker.finance.amount({ min: 4000, max: 45000, dec: 2 }),
        `Test order created by seeder - ${faker.lorem.sentence()}`
      ])
      
      if (order.rows.length > 0) {
        seededData.orders.push(order.rows[0])
        
        // Create order items
        const itemCount = faker.number.int({ min: 1, max: 5 })
        for (let j = 0; j < itemCount; j++) {
          const product = faker.helpers.arrayElement(seededData.products)
          const quantity = faker.number.int({ min: 1, max: 20 })
          const unitPrice = faker.finance.amount({ min: 800, max: 2000, dec: 2 })
          const lineTotal = (quantity * unitPrice).toFixed(2)
          
          await client.query(`
            INSERT INTO sales_order_items (
              sales_order_id, product_code, description, 
              quantity, unit_price, line_subtotal, line_total
            )
            VALUES ($1, $2, $3, $4, $5, $6, $6)
          `, [
            order.rows[0].id,
            product.product_code,
            `${product.name} - Test Item`,
            quantity,
            unitPrice,
            lineTotal
          ])
        }
      }
    }
    
    // Create test production plans
    const productionCount = options.productionCount || 3
    for (let i = 0; i < productionCount; i++) {
      await client.query(`
        INSERT INTO production_plans (
          company_id, plan_number, description, target_quantity,
          scheduled_date, status, notes
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7)
      `, [
        companyId,
        `TEST-PLAN-${i + 1}`,
        `Test Production Plan ${i + 1}`,
        faker.number.float({ min: 50.0, max: 500.0, fractionDigits: 2 }),
        faker.date.future({ days: 30 }),
        faker.helpers.arrayElement(['DRAFT', 'SCHEDULED', 'IN_PROGRESS', 'COMPLETED']),
        `Seeded production plan - ${faker.lorem.sentence()}`
      ])
    }
    
    await client.query('COMMIT')
    console.log('Test data seeding completed successfully')
    
    return seededData
    
  } catch (error) {
    await client.query('ROLLBACK')
    console.error('Seeding failed:', error)
    throw error
  } finally {
    await client.end()
  }
}

module.exports = async (options) => {
  try {
    return await seedTestData(options)
  } catch (error) {
    throw new Error(`Seeding task failed: ${error.message}`)
  }
}
