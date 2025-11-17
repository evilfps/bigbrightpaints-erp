const { Client } = require('pg')

const dbConfig = {
  host: process.env.DB_HOST || 'localhost',
  port: process.env.DB_PORT || 5432,
  database: process.env.DB_NAME || 'erp_domain',
  user: process.env.DB_USER || 'erp',
  password: process.env.DB_PASSWORD || 'erp'
}

async function queryDb(query, params = []) {
  const client = new Client(dbConfig)
  
  try {
    await client.connect()
    const result = await client.query(query, params)
    return result.rows
  } catch (error) {
    console.error('Database query error:', error)
    throw error
  } finally {
    await client.end()
  }
}

// Export task functions
module.exports = async ({ query, params }) => {
  try {
    const result = await queryDb(query, params)
    return result
  } catch (error) {
    throw new Error(`Database task failed: ${error.message}`)
  }
}

// Additional utility functions for common database operations
module.exports.getDealer = async (dealerId) => {
  return await queryDb(
    'SELECT * FROM dealers WHERE id = $1',
    [dealerId]
  )
}

module.exports.getOrder = async (orderId) => {
  return await queryDb(`
    SELECT so.*, d.name as dealer_name,
           array_agg(
             json_build_object(
               'id', soi.id,
               'productCode', soi.product_code,
               'quantity', soi.quantity,
               'unitPrice', soi.unit_price,
               'lineTotal', soi.line_total
             )
           ) as items
    FROM sales_orders so
    LEFT JOIN dealers d ON so.dealer_id = d.id
    LEFT JOIN sales_order_items soi ON so.id = soi.sales_order_id
    WHERE so.id = $1
    GROUP BY so.id, d.name
  `, [orderId])
}

module.exports.getJournalEntries = async (companyId) => {
  return await queryDb(`
    SELECT je.*, 
           array_agg(
             json_build_object(
               'accountId', jl.account_id,
               'description', jl.description,
               'debit', jl.debit,
               'credit', jl.credit
             )
           ) as lines
    FROM journal_entries je
    LEFT JOIN journal_lines jl ON je.id = jl.journal_entry_id
    WHERE je.company_id = $1
    GROUP BY je.id
    ORDER BY je.created_at DESC
  `, [companyId])
}

module.exports.getDealerLedger = async (dealerId) => {
  return await queryDb(`
    SELECT * FROM dealer_ledger_entries 
    WHERE dealer_id = $1 
    ORDER BY created_at ASC
  `, [dealerId])
}

module.exports.getInventoryMovements = async (companyId) => {
  return await queryDb(`
    SELECT im.*, fg.product_code, fgb.batch_code
    FROM inventory_movements im
    JOIN finished_goods fg ON im.finished_good_id = fg.id
    LEFT JOIN finished_good_batches fgb ON im.finished_good_batch_id = fgb.id
    WHERE fg.company_id = $1
    ORDER BY im.created_at DESC
  `, [companyId])
}

module.exports.getAccountBalance = async (accountId) => {
  return await queryDb(
    'SELECT balance FROM accounts WHERE id = $1',
    [accountId]
  )
}

module.exports.cleanupTestData = async (options = {}) => {
  const client = new Client(dbConfig)
  
  try {
    await client.connect()
    await client.query('BEGIN')
    
    // Clean up in order due to foreign key constraints
    if (options.email) {
      await client.query('DELETE FROM app_users WHERE email = $1', [options.email])
    }
    
    if (options.dealerCode) {
      await client.query('DELETE FROM dealers WHERE code = $1', [options.dealerCode])
    }
    
    if (options.orderNumber) {
      await client.query('DELETE FROM sales_orders WHERE order_number = $1', [options.orderNumber])
    }
    
    if (options.accountCode) {
      await client.query('DELETE FROM accounts WHERE code = $1', [options.accountCode])
    }
    
    if (options.testDataPrefix) {
      // Clean up all test data with a specific prefix
      await client.query(`
        DELETE FROM sales_orders 
        WHERE order_number LIKE $1 
           OR notes LIKE $2
      `, [`${options.testDataPrefix}%`, `%${options.testDataPrefix}%`])
      
      await client.query(`
        DELETE FROM dealers 
        WHERE code LIKE $1 
           OR name LIKE $2
      `, [`${options.testDataPrefix}%`, `%${options.testDataPrefix}%`])
      
      await client.query(`
        DELETE FROM accounts 
        WHERE code LIKE $1 
           OR name LIKE $2
      `, [`${options.testDataPrefix}%`, `%${options.testDataPrefix}%`])
    }
    
    await client.query('COMMIT')
    console.log('Test data cleanup completed')
    
  } catch (error) {
    await client.query('ROLLBACK')
    console.error('Cleanup failed:', error)
    throw error
  } finally {
    await client.end()
  }
}
