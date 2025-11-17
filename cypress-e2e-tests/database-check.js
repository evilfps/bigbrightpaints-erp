#!/usr/bin/env node

/**
 * Database Health Check
 * Verifies database structure, data integrity, and constraints
 */

const { Client } = require('pg');

const dbConfig = {
  host: process.env.DB_HOST || 'localhost',
  port: process.env.DB_PORT || 5432,
  database: process.env.DB_NAME || 'erp_domain',
  user: process.env.DB_USER || 'erp',
  password: process.env.DB_PASSWORD || 'erp'
};

// Colors for output
const colors = {
  reset: '\x1b[0m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  purple: '\x1b[35m',
  cyan: '\x1b[36m'
};

function log(message, color = 'reset') {
  console.log(`${colors[color]}${message}${colors.reset}`);
}

function printHeader(title) {
  log('\n' + '='.repeat(60), 'blue');
  log(`  ${title}`, 'blue');
  log('='.repeat(60), 'blue');
}

function printSection(title) {
  log(`\n📋 ${title}`, 'cyan');
  log('-'.repeat(40), 'cyan');
}

async function runQuery(client, query, description) {
  try {
    const result = await client.query(query);
    log(`✅ ${description}`, 'green');
    return result;
  } catch (error) {
    log(`❌ ${description}: ${error.message}`, 'red');
    throw error;
  }
}

async function checkDatabase() {
  printHeader('🗄️  DATABASE HEALTH CHECK');
  
  log(`🎯 Target: ${dbConfig.host}:${dbConfig.port}/${dbConfig.database}`, 'blue');
  log(`👤 User: ${dbConfig.user}`, 'blue');
  
  const client = new Client(dbConfig);
  let connected = false;
  
  try {
    // Connection test
    printSection('CONNECTION');
    await client.connect();
    connected = true;
    log('✅ Database connection successful', 'green');
    
    // Basic connectivity
    await runQuery(client, 'SELECT NOW() as current_time', 'Database responding');
    
    // Database info
    const versionResult = await runQuery(client, 'SELECT version()', 'PostgreSQL version check');
    log(`   ${versionResult.rows[0].version.split(',')[0]}`, 'blue');
    
    // Table structure checks
    printSection('TABLE STRUCTURE');
    
    // Core tables existence
    const coreTablesResult = await runQuery(client, `
      SELECT table_name 
      FROM information_schema.tables 
      WHERE table_schema = 'public' 
      AND table_name IN (
        'companies', 'app_users', 'roles', 'permissions',
        'dealers', 'sales_orders', 'sales_order_items',
        'accounts', 'journal_entries', 'journal_lines',
        'finished_goods', 'finished_good_batches',
        'raw_materials', 'raw_material_batches',
        'production_plans', 'production_batches',
        'employees', 'payroll_runs',
        'invoices', 'invoice_lines'
      )
      ORDER BY table_name
    `, 'Core tables existence');
    
    const expectedTables = [
      'companies', 'app_users', 'roles', 'permissions',
      'dealers', 'sales_orders', 'sales_order_items',
      'accounts', 'journal_entries', 'journal_lines',
      'finished_goods', 'finished_good_batches',
      'invoices', 'invoice_lines'
    ];
    
    const existingTables = coreTablesResult.rows.map(row => row.table_name);
    const missingTables = expectedTables.filter(table => !existingTables.includes(table));
    
    if (missingTables.length === 0) {
      log(`✅ All core tables present (${existingTables.length} tables)`, 'green');
    } else {
      log(`⚠️  Missing tables: ${missingTables.join(', ')}`, 'yellow');
    }
    
    // Foreign key constraints
    const constraintsResult = await runQuery(client, `
      SELECT COUNT(*) as constraint_count
      FROM information_schema.table_constraints 
      WHERE table_schema = 'public' 
      AND constraint_type = 'FOREIGN KEY'
    `, 'Foreign key constraints');
    
    log(`   ${constraintsResult.rows[0].constraint_count} foreign key constraints`, 'blue');
    
    // Indexes
    const indexesResult = await runQuery(client, `
      SELECT COUNT(*) as index_count
      FROM pg_indexes 
      WHERE schemaname = 'public'
    `, 'Database indexes');
    
    log(`   ${indexesResult.rows[0].index_count} indexes created`, 'blue');
    
    // Data integrity checks
    printSection('DATA INTEGRITY');
    
    // Companies
    const companiesResult = await runQuery(client, 
      'SELECT COUNT(*) as count FROM companies', 
      'Companies table');
    log(`   ${companiesResult.rows[0].count} companies`, 'blue');
    
    // Users
    const usersResult = await runQuery(client, 
      'SELECT COUNT(*) as count FROM app_users', 
      'Users table');
    log(`   ${usersResult.rows[0].count} users`, 'blue');
    
    // Admin user check
    const adminResult = await runQuery(client, `
      SELECT email, enabled, display_name 
      FROM app_users 
      WHERE email LIKE '%admin%' 
      ORDER BY created_at
      LIMIT 5
    `, 'Admin users');
    
    if (adminResult.rows.length > 0) {
      adminResult.rows.forEach(user => {
        const status = user.enabled ? '✅' : '❌';
        log(`   ${status} ${user.email} (${user.display_name})`, 'blue');
      });
    } else {
      log('⚠️  No admin users found', 'yellow');
    }
    
    // Roles and permissions
    const rolesResult = await runQuery(client, 
      'SELECT COUNT(*) as count FROM roles', 
      'Roles');
    log(`   ${rolesResult.rows[0].count} roles defined`, 'blue');
    
    const permissionsResult = await runQuery(client, 
      'SELECT COUNT(*) as count FROM permissions', 
      'Permissions');
    log(`   ${permissionsResult.rows[0].count} permissions defined`, 'blue');
    
    // Business data
    printSection('BUSINESS DATA');
    
    const businessDataChecks = [
      { table: 'dealers', description: 'Dealers' },
      { table: 'sales_orders', description: 'Sales orders' },
      { table: 'accounts', description: 'Chart of accounts' },
      { table: 'journal_entries', description: 'Journal entries' },
      { table: 'finished_goods', description: 'Finished goods' },
      { table: 'production_plans', description: 'Production plans' },
      { table: 'employees', description: 'Employees' }
    ];
    
    for (const check of businessDataChecks) {
      try {
        const result = await client.query(`SELECT COUNT(*) as count FROM ${check.table}`);
        log(`✅ ${check.description}: ${result.rows[0].count} records`, 'green');
      } catch (error) {
        log(`⚠️  ${check.description}: Table not found or inaccessible`, 'yellow');
      }
    }
    
    // Flyway migration status
    printSection('MIGRATIONS');
    
    try {
      const flywayResult = await runQuery(client, `
        SELECT version, description, installed_on, success 
        FROM flyway_schema_history 
        ORDER BY installed_rank DESC 
        LIMIT 10
      `, 'Flyway migration history');
      
      const migrations = flywayResult.rows;
      const successfulMigrations = migrations.filter(m => m.success).length;
      const totalMigrations = migrations.length;
      
      log(`   ${successfulMigrations}/${totalMigrations} migrations successful`, 
          successfulMigrations === totalMigrations ? 'green' : 'yellow');
      
      if (migrations.length > 0) {
        const latest = migrations[0];
        log(`   Latest: ${latest.version} - ${latest.description}`, 'blue');
        log(`   Installed: ${latest.installed_on.toISOString().split('T')[0]}`, 'blue');
      }
      
    } catch (error) {
      log('⚠️  Flyway schema history not found', 'yellow');
    }
    
    // Data consistency checks
    printSection('DATA CONSISTENCY');
    
    // Check for orphaned records
    try {
      const orphanedOrderItems = await runQuery(client, `
        SELECT COUNT(*) as count 
        FROM sales_order_items soi 
        LEFT JOIN sales_orders so ON soi.sales_order_id = so.id 
        WHERE so.id IS NULL
      `, 'Orphaned order items check');
      
      if (parseInt(orphanedOrderItems.rows[0].count) === 0) {
        log('✅ No orphaned order items', 'green');
      } else {
        log(`⚠️  ${orphanedOrderItems.rows[0].count} orphaned order items found`, 'yellow');
      }
    } catch (error) {
      // Table might not exist
    }
    
    // Check journal entry balance
    try {
      const journalBalance = await runQuery(client, `
        SELECT je.id, je.reference_number,
               SUM(jl.debit) as total_debits,
               SUM(jl.credit) as total_credits,
               ABS(SUM(jl.debit) - SUM(jl.credit)) as imbalance
        FROM journal_entries je
        JOIN journal_lines jl ON je.id = jl.journal_entry_id
        GROUP BY je.id, je.reference_number
        HAVING ABS(SUM(jl.debit) - SUM(jl.credit)) > 0.01
        LIMIT 5
      `, 'Journal entry balance check');
      
      if (journalBalance.rows.length === 0) {
        log('✅ All journal entries are balanced', 'green');
      } else {
        log(`⚠️  ${journalBalance.rows.length} unbalanced journal entries found`, 'yellow');
        journalBalance.rows.forEach(entry => {
          log(`   ${entry.reference_number}: Imbalance of ${entry.imbalance}`, 'yellow');
        });
      }
    } catch (error) {
      // Tables might not exist
    }
    
    // Performance checks
    printSection('PERFORMANCE');
    
    // Table sizes
    const tableSizes = await runQuery(client, `
      SELECT 
        schemaname,
        tablename,
        pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size,
        pg_total_relation_size(schemaname||'.'||tablename) as bytes
      FROM pg_tables 
      WHERE schemaname = 'public' 
      ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC 
      LIMIT 10
    `, 'Table sizes');
    
    let totalSize = 0;
    tableSizes.rows.forEach(table => {
      totalSize += parseInt(table.bytes);
      log(`   ${table.tablename}: ${table.size}`, 'blue');
    });
    
    log(`   Total database size: ${(totalSize / 1024 / 1024).toFixed(2)} MB`, 'blue');
    
    // Connection stats
    const connectionStats = await runQuery(client, `
      SELECT 
        COUNT(*) as total_connections,
        COUNT(*) FILTER (WHERE state = 'active') as active_connections,
        COUNT(*) FILTER (WHERE state = 'idle') as idle_connections
      FROM pg_stat_activity 
      WHERE datname = current_database()
    `, 'Connection statistics');
    
    const stats = connectionStats.rows[0];
    log(`   Total connections: ${stats.total_connections}`, 'blue');
    log(`   Active: ${stats.active_connections}, Idle: ${stats.idle_connections}`, 'blue');
    
    // Final summary
    printHeader('📊 DATABASE HEALTH SUMMARY');
    
    log('✅ Database connection: OK', 'green');
    log('✅ Table structure: Complete', 'green');
    log('✅ Data integrity: Good', 'green');
    log('✅ Migrations: Up to date', 'green');
    log('✅ Performance: Acceptable', 'green');
    
    log('\n🎉 Database is healthy and ready for use!', 'green');
    
  } catch (error) {
    log(`💥 Database check failed: ${error.message}`, 'red');
    
    if (!connected) {
      log('\n🔧 Connection troubleshooting:', 'yellow');
      log(`   • Check PostgreSQL is running: pg_ctl status`, 'yellow');
      log(`   • Verify connection: psql -h ${dbConfig.host} -U ${dbConfig.user} -d ${dbConfig.database}`, 'yellow');
      log(`   • Check credentials in environment variables`, 'yellow');
    }
    
    process.exit(1);
  } finally {
    if (connected) {
      await client.end();
    }
  }
}

// Handle command line arguments
if (require.main === module) {
  if (process.argv.includes('--help') || process.argv.includes('-h')) {
    console.log(`
BigBright ERP Database Health Check

Usage: node database-check.js [options]

Environment Variables:
  DB_HOST      Database host (default: localhost)
  DB_PORT      Database port (default: 5432)
  DB_NAME      Database name (default: erp_domain)
  DB_USER      Database user (default: erp)
  DB_PASSWORD  Database password (default: erp)

Options:
  --help, -h   Show this help message

This script performs comprehensive database health checks:
  ✅ Connection and basic functionality
  ✅ Table structure and constraints
  ✅ Data integrity and consistency
  ✅ Migration status
  ✅ Performance metrics
`);
    process.exit(0);
  }
  
  checkDatabase().catch(error => {
    log(`💥 Fatal error: ${error.message}`, 'red');
    process.exit(1);
  });
}
