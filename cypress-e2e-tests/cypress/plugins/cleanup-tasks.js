const { Client } = require('pg')

const dbConfig = {
  host: process.env.DB_HOST || 'localhost',
  port: process.env.DB_PORT || 5432,
  database: process.env.DB_NAME || 'erp_domain',
  user: process.env.DB_USER || 'erp',
  password: process.env.DB_PASSWORD || 'erp'
}

async function cleanupTestData(options = {}) {
  const client = new Client(dbConfig)
  
  try {
    await client.connect()
    await client.query('BEGIN')
    
    console.log('Starting test data cleanup...')
    
    // Clean up in reverse order of creation due to foreign key constraints
    
    // 1. Clean up order items first
    if (options.cleanupOrders) {
      await client.query(`
        DELETE FROM sales_order_items 
        WHERE sales_order_id IN (
          SELECT id FROM sales_orders 
          WHERE order_number LIKE 'TEST-%' 
             OR order_number LIKE 'CYP-%'
             OR order_number LIKE 'O2C-%'
             OR notes LIKE '%Cypress%'
             OR notes LIKE '%test%'
        )
      `)
      console.log('Cleaned up test order items')
    }
    
    // 2. Clean up sales orders
    if (options.cleanupOrders) {
      await client.query(`
        DELETE FROM sales_orders 
        WHERE order_number LIKE 'TEST-%' 
           OR order_number LIKE 'CYP-%'
           OR order_number LIKE 'O2C-%'
           OR notes LIKE '%Cypress%'
           OR notes LIKE '%test%'
      `)
      console.log('Cleaned up test sales orders')
    }
    
    // 3. Clean up inventory reservations
    if (options.cleanupInventory) {
      await client.query(`
        DELETE FROM inventory_reservations 
        WHERE reference_id LIKE 'TEST-%'
           OR reference_id LIKE 'CYP-%'
      `)
      console.log('Cleaned up test inventory reservations')
    }
    
    // 4. Clean up finished good batches
    if (options.cleanupInventory) {
      await client.query(`
        DELETE FROM finished_good_batches 
        WHERE finished_good_id IN (
          SELECT id FROM finished_goods 
          WHERE product_code LIKE 'TEST-%'
             OR product_code LIKE 'O2C-%'
        )
      `)
      console.log('Cleaned up test finished good batches')
    }
    
    // 5. Clean up finished goods
    if (options.cleanupInventory) {
      await client.query(`
        DELETE FROM finished_goods 
        WHERE product_code LIKE 'TEST-%'
           OR product_code LIKE 'O2C-%'
           OR name LIKE '%Test%'
           OR name LIKE '%Cypress%'
      `)
      console.log('Cleaned up test finished goods')
    }
    
    // 6. Clean up production logs and related data
    if (options.cleanupProduction) {
      await client.query(`
        DELETE FROM production_log_materials
        WHERE production_log_id IN (
          SELECT id FROM production_logs
          WHERE notes LIKE '%test%' OR notes LIKE '%Test%'
        )
      `)
      
      await client.query(`
        DELETE FROM production_logs 
        WHERE notes LIKE '%test%' 
           OR notes LIKE '%Test%'
           OR notes LIKE '%Cypress%'
      `)
      
      await client.query(`
        DELETE FROM production_batches
        WHERE batch_code LIKE 'TEST-%'
           OR notes LIKE '%test%'
      `)
      
      await client.query(`
        DELETE FROM production_plans 
        WHERE plan_number LIKE 'TEST-%'
           OR notes LIKE '%test%'
           OR notes LIKE '%Seeded%'
      `)
      console.log('Cleaned up test production data')
    }
    
    // 7. Clean up journal entries and lines
    if (options.cleanupAccounting) {
      await client.query(`
        DELETE FROM journal_lines 
        WHERE journal_entry_id IN (
          SELECT id FROM journal_entries 
          WHERE reference_number LIKE 'TEST-%'
             OR memo LIKE '%test%'
             OR memo LIKE '%Test%'
             OR memo LIKE '%Cypress%'
        )
      `)
      
      await client.query(`
        DELETE FROM journal_entries 
        WHERE reference_number LIKE 'TEST-%'
           OR memo LIKE '%test%'
           OR memo LIKE '%Test%'
           OR memo LIKE '%Cypress%'
      `)
      console.log('Cleaned up test journal entries')
    }
    
    // 8. Clean up dealer ledger entries
    if (options.cleanupDealers) {
      await client.query(`
        DELETE FROM dealer_ledger_entries 
        WHERE dealer_id IN (
          SELECT id FROM dealers 
          WHERE code LIKE 'TEST-%'
             OR code LIKE 'CYP-%'
             OR code LIKE 'O2C-%'
             OR name LIKE '%Test%'
             OR name LIKE '%Cypress%'
        )
      `)
      console.log('Cleaned up test dealer ledger entries')
    }
    
    // 9. Clean up dealers
    if (options.cleanupDealers) {
      await client.query(`
        DELETE FROM dealers 
        WHERE code LIKE 'TEST-%'
           OR code LIKE 'CYP-%'
           OR code LIKE 'O2C-%'
           OR code LIKE 'UI-%'
           OR code LIKE 'API-%'
           OR name LIKE '%Test%'
           OR name LIKE '%Cypress%'
           OR email LIKE '%test%'
           OR email LIKE '%cypress%'
      `)
      console.log('Cleaned up test dealers')
    }
    
    // 10. Clean up test accounts
    if (options.cleanupAccounts) {
      await client.query(`
        DELETE FROM accounts 
        WHERE code LIKE 'TEST-%'
           OR code LIKE 'AR-TEST-%'
           OR code LIKE 'AR-CYP-%'
           OR code LIKE 'AR-O2C-%'
           OR name LIKE '%Test%'
           OR name LIKE '%Cypress%'
      `)
      console.log('Cleaned up test accounts')
    }
    
    // 11. Clean up test users (be careful with this)
    if (options.cleanupUsers) {
      await client.query(`
        DELETE FROM user_roles 
        WHERE user_id IN (
          SELECT id FROM app_users 
          WHERE email LIKE '%test%' 
             OR email LIKE '%cypress%'
             OR email NOT IN ('admin@bbp.dev')
        )
      `)
      
      await client.query(`
        DELETE FROM user_companies 
        WHERE user_id IN (
          SELECT id FROM app_users 
          WHERE email LIKE '%test%' 
             OR email LIKE '%cypress%'
             OR email NOT IN ('admin@bbp.dev')
        )
      `)
      
      await client.query(`
        DELETE FROM app_users 
        WHERE email LIKE '%test%' 
           OR email LIKE '%cypress%'
           AND email NOT IN ('admin@bbp.dev')
      `)
      console.log('Cleaned up test users')
    }
    
    // 12. Clean up order auto approval states
    if (options.cleanupOrchestration) {
      await client.query(`
        DELETE FROM order_auto_approval_states
        WHERE company_code = 'BBP'
          AND order_id NOT IN (SELECT id FROM sales_orders)
      `)
      console.log('Cleaned up orphaned auto approval states')
    }
    
    // 13. Clean up outbox events
    if (options.cleanupOrchestration) {
      await client.query(`
        DELETE FROM outbox_events 
        WHERE entity_id LIKE 'TEST-%'
           OR entity_id LIKE 'CYP-%'
           OR payload::text LIKE '%test%'
           OR created_at < NOW() - INTERVAL '1 day'
      `)
      console.log('Cleaned up test outbox events')
    }
    
    // Specific cleanup by identifiers
    if (options.email) {
      await client.query(`
        DELETE FROM app_users WHERE email = $1
      `, [options.email])
      console.log(`Cleaned up user: ${options.email}`)
    }
    
    if (options.dealerCode) {
      await client.query(`
        DELETE FROM dealers WHERE code = $1
      `, [options.dealerCode])
      console.log(`Cleaned up dealer: ${options.dealerCode}`)
    }
    
    if (options.orderNumber) {
      await client.query(`
        DELETE FROM sales_order_items 
        WHERE sales_order_id IN (
          SELECT id FROM sales_orders WHERE order_number = $1
        )
      `, [options.orderNumber])
      
      await client.query(`
        DELETE FROM sales_orders WHERE order_number = $1
      `, [options.orderNumber])
      console.log(`Cleaned up order: ${options.orderNumber}`)
    }
    
    await client.query('COMMIT')
    console.log('Test data cleanup completed successfully')
    
    return { success: true, message: 'Cleanup completed' }
    
  } catch (error) {
    await client.query('ROLLBACK')
    console.error('Cleanup failed:', error)
    throw error
  } finally {
    await client.end()
  }
}

module.exports = async (options) => {
  try {
    const defaultOptions = {
      cleanupOrders: true,
      cleanupDealers: true,
      cleanupAccounts: true,
      cleanupInventory: true,
      cleanupProduction: true,
      cleanupAccounting: true,
      cleanupOrchestration: true,
      cleanupUsers: false, // Be careful with this
      ...options
    }
    
    return await cleanupTestData(defaultOptions)
  } catch (error) {
    throw new Error(`Cleanup task failed: ${error.message}`)
  }
}
