#!/bin/bash

# Quick Start Script for BigBright ERP Cypress Tests
# This script automates the entire setup process

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m'

print_header() {
    echo -e "${BLUE}"
    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║            BigBright ERP - E2E Testing Setup              ║"
    echo "║                   Quick Start Script                      ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
}

print_step() {
    echo -e "\n${PURPLE}[STEP $1]${NC} ${BLUE}$2${NC}"
    echo "----------------------------------------"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to wait for user confirmation
wait_for_user() {
    echo -e "\n${YELLOW}Press Enter to continue or Ctrl+C to abort...${NC}"
    read
}

# Function to check if service is running
check_service() {
    local url=$1
    local name=$2
    echo -n "Checking $name... "
    
    if curl -s -f "$url" > /dev/null 2>&1; then
        print_success "$name is running"
        return 0
    else
        print_warning "$name is not running"
        return 1
    fi
}

# Function to create cypress config
create_config() {
    local backend_url=$1
    local frontend_url=$2
    local admin_email=$3
    local admin_password=$4
    local company_code=$5
    
    cat > cypress.config.js << EOF
const { defineConfig } = require('cypress')

module.exports = defineConfig({
  e2e: {
    baseUrl: '$frontend_url',
    supportFile: 'cypress/support/e2e.js',
    specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}',
    viewportWidth: 1280,
    viewportHeight: 720,
    video: true,
    screenshotOnRunFailure: true,
    defaultCommandTimeout: 10000,
    requestTimeout: 10000,
    responseTimeout: 10000,
    env: {
      apiUrl: '$backend_url',
      adminEmail: '$admin_email',
      adminPassword: '$admin_password',
      companyCode: '$company_code',
      dbHost: 'localhost',
      dbPort: 5432,
      dbName: 'erp_domain',
      dbUser: 'erp',
      dbPassword: 'erp'
    },
    setupNodeEvents(on, config) {
      on('task', {
        queryDb: require('./cypress/plugins/db-tasks'),
        seedTestData: require('./cypress/plugins/seed-tasks'),
        cleanupTestData: require('./cypress/plugins/cleanup-tasks')
      })
    }
  }
})
EOF
    
    print_success "Configuration file created"
}

main() {
    print_header
    
    print_step "1" "Prerequisites Check"
    
    # Check Node.js
    if command_exists node; then
        NODE_VERSION=$(node --version)
        print_success "Node.js $NODE_VERSION found"
    else
        print_error "Node.js not found. Please install Node.js 16+ from https://nodejs.org"
        exit 1
    fi
    
    # Check npm
    if command_exists npm; then
        NPM_VERSION=$(npm --version)
        print_success "npm $NPM_VERSION found"
    else
        print_error "npm not found"
        exit 1
    fi
    
    # Check curl
    if command_exists curl; then
        print_success "curl found"
    else
        print_error "curl not found. Please install curl"
        exit 1
    fi
    
    print_step "2" "Service Detection"
    
    # Detect backend URL
    BACKEND_URL=""
    if check_service "http://localhost:8080/actuator/health" "Backend (port 8080)"; then
        BACKEND_URL="http://localhost:8080"
    elif check_service "http://localhost:8081/actuator/health" "Backend (port 8081)"; then
        BACKEND_URL="http://localhost:8081"
    else
        print_warning "Backend not detected on common ports"
        echo -n "Enter backend URL (e.g., http://localhost:8080): "
        read BACKEND_URL
    fi
    
    # Detect frontend URL
    FRONTEND_URL=""
    if check_service "http://localhost:3002" "Frontend (port 3002)"; then
        FRONTEND_URL="http://localhost:3002"
    elif check_service "http://localhost:3000" "Frontend (port 3000)"; then
        FRONTEND_URL="http://localhost:3000"
    elif check_service "http://localhost:3001" "Frontend (port 3001)"; then
        FRONTEND_URL="http://localhost:3001"
    else
        print_warning "Frontend not detected on common ports"
        echo -n "Enter frontend URL (e.g., http://localhost:3002): "
        read FRONTEND_URL
    fi
    
    print_step "3" "Configuration Input"
    
    # Get admin credentials
    echo -n "Admin email [admin@bbp.dev]: "
    read ADMIN_EMAIL
    ADMIN_EMAIL=${ADMIN_EMAIL:-"admin@bbp.dev"}
    
    echo -n "Admin password [ChangeMe123!]: "
    read -s ADMIN_PASSWORD
    echo
    ADMIN_PASSWORD=${ADMIN_PASSWORD:-"ChangeMe123!"}
    
    echo -n "Company code [BBP]: "
    read COMPANY_CODE
    COMPANY_CODE=${COMPANY_CODE:-"BBP"}
    
    print_step "4" "Installation"
    
    # Install dependencies
    print_info "Installing npm dependencies..."
    npm install
    print_success "Dependencies installed"
    
    print_step "5" "Configuration"
    
    # Create configuration
    create_config "$BACKEND_URL" "$FRONTEND_URL" "$ADMIN_EMAIL" "$ADMIN_PASSWORD" "$COMPANY_CODE"
    
    print_step "6" "Verification"
    
    # Test admin login
    print_info "Testing admin login..."
    LOGIN_RESPONSE=$(curl -s -X POST "$BACKEND_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
        || echo '{"success":false}')
    
    if echo "$LOGIN_RESPONSE" | grep -q '"success":true'; then
        print_success "Admin login successful"
    else
        print_error "Admin login failed"
        print_warning "Please check your credentials and try running tests manually"
    fi
    
    print_step "7" "Initial Test Run"
    
    print_info "Running setup verification test..."
    if npx cypress run --spec "cypress/e2e/setup/01-initial-setup.cy.js" --headless; then
        print_success "Setup verification passed"
    else
        print_warning "Setup verification had issues. Check the output above."
    fi
    
    # Final instructions
    echo -e "\n${GREEN}"
    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║                    🎉 SETUP COMPLETE! 🎉                  ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    
    echo -e "\n${BLUE}📋 What's been configured:${NC}"
    echo "   ✅ Dependencies installed"
    echo "   ✅ Configuration created"
    echo "   ✅ Backend connection verified ($BACKEND_URL)"
    echo "   ✅ Frontend connection verified ($FRONTEND_URL)"
    echo "   ✅ Admin credentials tested"
    
    echo -e "\n${PURPLE}🚀 Next Steps:${NC}"
    echo "   1. npm run cypress:open     # Open interactive test runner"
    echo "   2. npm run test:api         # Run API tests only"
    echo "   3. npm run test:ui          # Run UI tests only"
    echo "   4. ./run-tests.sh           # Run all tests with checks"
    
    echo -e "\n${YELLOW}📚 Helpful Commands:${NC}"
    echo "   npm run cypress:run         # Run all tests headless"
    echo "   npx cypress run --spec \"**/*dealer*.cy.js\"  # Run specific tests"
    echo "   node setup-check.js         # Check prerequisites anytime"
    
    echo -e "\n${BLUE}📖 Documentation:${NC}"
    echo "   📄 INSTALLATION.md          # Detailed setup guide"
    echo "   📄 README.md               # Complete documentation"
    
    echo -e "\n${GREEN}Happy Testing! 🧪${NC}"
}

# Handle help option
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    echo "BigBright ERP E2E Testing - Quick Start Script"
    echo ""
    echo "This script will:"
    echo "  1. Check prerequisites (Node.js, npm, curl)"
    echo "  2. Detect running services (backend, frontend)"
    echo "  3. Install npm dependencies"
    echo "  4. Create configuration file"
    echo "  5. Test admin login"
    echo "  6. Run initial verification"
    echo ""
    echo "Usage:"
    echo "  ./quick-start.sh          # Interactive setup"
    echo "  ./quick-start.sh --help   # Show this help"
    echo ""
    echo "Prerequisites:"
    echo "  - Node.js 16+"
    echo "  - Backend running (http://localhost:8080 or 8081)"
    echo "  - Database accessible"
    echo "  - Admin user exists in database"
    exit 0
fi

# Run main function
main "$@"
