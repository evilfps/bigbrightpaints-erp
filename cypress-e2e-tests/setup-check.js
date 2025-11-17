#!/usr/bin/env node

/**
 * Prerequisites Checker for BigBright ERP E2E Tests
 * Run this script to verify your environment is ready
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

// Colors for console output
const colors = {
  reset: '\x1b[0m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m'
};

function log(message, color = 'reset') {
  console.log(`${colors[color]}${message}${colors.reset}`);
}

function checkCommand(command, description) {
  try {
    execSync(command, { stdio: 'ignore' });
    log(`✅ ${description} - OK`, 'green');
    return true;
  } catch (error) {
    log(`❌ ${description} - NOT FOUND`, 'red');
    return false;
  }
}

function checkUrl(url, description) {
  try {
    const response = execSync(`curl -s -o /dev/null -w "%{http_code}" ${url}`, { encoding: 'utf8' });
    if (response.trim() === '200' || response.trim() === '000') {
      log(`✅ ${description} - ACCESSIBLE`, 'green');
      return true;
    } else {
      log(`⚠️  ${description} - Status: ${response.trim()}`, 'yellow');
      return false;
    }
  } catch (error) {
    log(`❌ ${description} - NOT ACCESSIBLE`, 'red');
    return false;
  }
}

function checkFile(filePath, description) {
  if (fs.existsSync(filePath)) {
    log(`✅ ${description} - EXISTS`, 'green');
    return true;
  } else {
    log(`❌ ${description} - MISSING`, 'red');
    return false;
  }
}

function main() {
  log('🔍 BigBright ERP E2E Testing - Prerequisites Check', 'blue');
  log('=' .repeat(60), 'blue');
  
  let allGood = true;
  
  // Check Node.js and npm
  log('\n📦 Package Manager:', 'blue');
  allGood &= checkCommand('node --version', 'Node.js (>= 16.0.0)');
  allGood &= checkCommand('npm --version', 'npm');
  
  // Check if we can install global packages
  try {
    const nodeVersion = execSync('node --version', { encoding: 'utf8' });
    const version = parseInt(nodeVersion.replace('v', '').split('.')[0]);
    if (version < 16) {
      log(`⚠️  Node.js version ${nodeVersion.trim()} detected. Version 16+ recommended`, 'yellow');
    }
  } catch (error) {
    // Node version check failed
  }
  
  // Check required tools
  log('\n🛠️  Required Tools:', 'blue');
  allGood &= checkCommand('curl --version', 'curl (for API testing)');
  
  // Check optional tools
  log('\n🔧 Optional Tools:', 'blue');
  checkCommand('psql --version', 'PostgreSQL client (for direct DB access)');
  checkCommand('git --version', 'Git (for version control)');
  
  // Check backend service
  log('\n🖥️  Backend Services:', 'blue');
  allGood &= checkUrl('http://localhost:8080/actuator/health', 'ERP Backend (http://localhost:8080)');
  checkUrl('http://localhost:8081/actuator/health', 'ERP Backend (http://localhost:8081) - Alternative port');
  
  // Check frontend service  
  log('\n🌐 Frontend Services:', 'blue');
  checkUrl('http://localhost:3002', 'React Frontend (http://localhost:3002)');
  checkUrl('http://localhost:3000', 'React Frontend (http://localhost:3000) - Alternative port');
  
  // Check database connection
  log('\n🗄️  Database:', 'blue');
  try {
    // Try to connect to PostgreSQL
    execSync('PGPASSWORD=erp psql -h localhost -U erp -d erp_domain -c "SELECT 1;" 2>/dev/null', { stdio: 'ignore' });
    log('✅ PostgreSQL Database - ACCESSIBLE', 'green');
  } catch (error) {
    log('❌ PostgreSQL Database - NOT ACCESSIBLE', 'red');
    log('   Connection string: postgresql://erp:erp@localhost:5432/erp_domain', 'yellow');
    allGood = false;
  }
  
  // Check project structure
  log('\n📁 Project Structure:', 'blue');
  const baseDir = path.resolve(__dirname, '..');
  checkFile(path.join(baseDir, 'erp-domain', 'pom.xml'), 'Backend project (erp-domain)');
  
  // Look for frontend directory
  const possibleFrontendDirs = [
    'FRONTEND OF BACKEND',
    'frontend', 
    'ui', 
    'client',
    'web-app'
  ];
  
  let frontendFound = false;
  for (const dir of possibleFrontendDirs) {
    const frontendPath = path.join(baseDir, dir);
    if (fs.existsSync(frontendPath)) {
      if (fs.existsSync(path.join(frontendPath, 'package.json'))) {
        log(`✅ Frontend project (${dir}) - EXISTS`, 'green');
        frontendFound = true;
        break;
      }
    }
  }
  
  if (!frontendFound) {
    log('⚠️  Frontend project - NOT FOUND (or not a Node.js project)', 'yellow');
    log('   Expected: package.json in frontend directory', 'yellow');
  }
  
  // Final verdict
  log('\n' + '=' .repeat(60), 'blue');
  if (allGood) {
    log('🎉 All critical prerequisites are met! You can proceed with installation.', 'green');
    log('\n📝 Next Steps:', 'blue');
    log('   1. cd cypress-e2e-tests', 'reset');
    log('   2. npm install', 'reset');
    log('   3. Update cypress.config.js with your settings', 'reset');
    log('   4. npm run cypress:open', 'reset');
  } else {
    log('⚠️  Some prerequisites are missing. Please fix the issues above.', 'yellow');
    log('\n🔧 Common Solutions:', 'blue');
    log('   • Backend not running: cd erp-domain && ./mvnw spring-boot:run', 'reset');
    log('   • Frontend not running: cd your-frontend-dir && npm start', 'reset');
    log('   • Database not accessible: Check PostgreSQL is running and credentials', 'reset');
    log('   • Node.js too old: Install Node.js 16+ from nodejs.org', 'reset');
  }
}

if (require.main === module) {
  main();
}
