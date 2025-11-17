#!/bin/bash

# BigBright ERP - E2E Test Runner Script
# This script sets up the environment and runs Cypress tests

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BACKEND_URL="http://localhost:8080"
FRONTEND_URL="http://localhost:3002"
HEALTH_CHECK_TIMEOUT=120
TEST_TIMEOUT=600

# Function to print colored output
print_status() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')] $1${NC}"
}

print_success() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')] ✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')] ⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')] ✗ $1${NC}"
}

# Function to check if a service is running
check_service() {
    local url=$1
    local service_name=$2
    local timeout=${3:-30}
    
    print_status "Checking if $service_name is running at $url..."
    
    local count=0
    while [ $count -lt $timeout ]; do
        if curl -s -f "$url" > /dev/null 2>&1; then
            print_success "$service_name is running"
            return 0
        fi
        sleep 2
        count=$((count + 2))
        echo -n "."
    done
    
    print_error "$service_name is not responding at $url"
    return 1
}

# Function to wait for database
check_database() {
    print_status "Checking database connectivity..."
    
    # Try to connect to PostgreSQL
    if command -v psql > /dev/null; then
        if PGPASSWORD=erp psql -h localhost -U erp -d erp_domain -c "SELECT 1;" > /dev/null 2>&1; then
            print_success "Database connection successful"
            return 0
        fi
    fi
    
    print_warning "Direct database connection failed, will rely on backend health check"
    return 0
}

# Function to setup test data
setup_test_data() {
    print_status "Setting up test data..."
    
    # Check if admin user exists
    local response=$(curl -s -X POST "$BACKEND_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"email":"admin@bbp.dev","password":"ChangeMe123!"}' 2>/dev/null || echo "")
    
    if echo "$response" | grep -q '"success":true'; then
        print_success "Admin user verified"
    else
        print_warning "Admin user not found or credentials incorrect"
        print_warning "Make sure to run the backend seed scripts first"
    fi
}

# Function to clean up old test data
cleanup_test_data() {
    print_status "Cleaning up old test data..."
    
    if [ -f "cypress/plugins/cleanup-tasks.js" ]; then
        # This would ideally call the cleanup task
        print_success "Cleanup task available"
    else
        print_warning "Cleanup task not found, manual cleanup may be needed"
    fi
}

# Function to run specific test suite
run_tests() {
    local test_type=${1:-"all"}
    local spec_pattern=""
    
    case $test_type in
        "api")
            spec_pattern="cypress/e2e/api/**/*.cy.js"
            print_status "Running API tests..."
            ;;
        "ui")
            spec_pattern="cypress/e2e/ui/**/*.cy.js"
            print_status "Running UI tests..."
            ;;
        "workflows")
            spec_pattern="cypress/e2e/workflows/**/*.cy.js"
            print_status "Running workflow tests..."
            ;;
        "all")
            spec_pattern="cypress/e2e/**/*.cy.js"
            print_status "Running all tests..."
            ;;
        *)
            print_error "Unknown test type: $test_type"
            echo "Available options: api, ui, workflows, all"
            exit 1
            ;;
    esac
    
    # Set environment variables
    export CYPRESS_baseUrl="$FRONTEND_URL"
    export CYPRESS_apiUrl="$BACKEND_URL"
    
    # Run Cypress tests
    if [ "$CI" = "true" ] || [ "$HEADLESS" = "true" ]; then
        print_status "Running in headless mode..."
        npx cypress run --spec "$spec_pattern" --reporter json --reporter-options output=test-results.json
    else
        print_status "Running in interactive mode..."
        npx cypress open --config specPattern="$spec_pattern"
    fi
}

# Function to generate test report
generate_report() {
    if [ -f "test-results.json" ]; then
        print_status "Generating test report..."
        
        local total_tests=$(jq '.stats.tests' test-results.json 2>/dev/null || echo "0")
        local passed_tests=$(jq '.stats.passes' test-results.json 2>/dev/null || echo "0")
        local failed_tests=$(jq '.stats.failures' test-results.json 2>/dev/null || echo "0")
        local duration=$(jq '.stats.duration' test-results.json 2>/dev/null || echo "0")
        
        echo -e "\n${BLUE}========================================${NC}"
        echo -e "${BLUE}           TEST RESULTS SUMMARY        ${NC}"
        echo -e "${BLUE}========================================${NC}"
        echo -e "Total Tests:  ${total_tests}"
        echo -e "Passed:       ${GREEN}${passed_tests}${NC}"
        echo -e "Failed:       ${RED}${failed_tests}${NC}"
        echo -e "Duration:     ${duration}ms"
        echo -e "${BLUE}========================================${NC}\n"
        
        if [ "$failed_tests" -gt 0 ]; then
            print_error "Some tests failed!"
            return 1
        else
            print_success "All tests passed!"
            return 0
        fi
    else
        print_warning "Test results file not found"
        return 0
    fi
}

# Main execution function
main() {
    local test_type=${1:-"all"}
    local skip_checks=${2:-false}
    
    echo -e "${BLUE}"
    echo "=========================================="
    echo "    BigBright ERP - E2E Test Runner      "
    echo "=========================================="
    echo -e "${NC}"
    
    # Check if we're in the right directory
    if [ ! -f "cypress.config.js" ]; then
        print_error "cypress.config.js not found. Please run from the cypress-e2e-tests directory"
        exit 1
    fi
    
    # Install dependencies if needed
    if [ ! -d "node_modules" ]; then
        print_status "Installing dependencies..."
        npm install
    fi
    
    # Skip checks if requested (useful for development)
    if [ "$skip_checks" != "true" ]; then
        # Check prerequisites
        print_status "Checking prerequisites..."
        
        # Check if required services are running
        if ! check_service "$BACKEND_URL/actuator/health" "Backend" 30; then
            print_error "Backend service is required. Please start the ERP backend first."
            exit 1
        fi
        
        if [ "$test_type" = "ui" ] || [ "$test_type" = "workflows" ] || [ "$test_type" = "all" ]; then
            if ! check_service "$FRONTEND_URL" "Frontend" 30; then
                print_error "Frontend service is required for UI tests. Please start the React frontend first."
                exit 1
            fi
        fi
        
        # Check database connectivity
        check_database
        
        # Setup test data
        setup_test_data
        
        # Cleanup old test data
        cleanup_test_data
    else
        print_warning "Skipping prerequisite checks"
    fi
    
    # Run tests
    print_status "Starting test execution..."
    
    local test_start_time=$(date +%s)
    
    if run_tests "$test_type"; then
        local test_end_time=$(date +%s)
        local test_duration=$((test_end_time - test_start_time))
        
        print_success "Tests completed in ${test_duration} seconds"
        
        # Generate report
        generate_report
        
        # Cleanup after tests
        if [ "$SKIP_CLEANUP" != "true" ]; then
            cleanup_test_data
        fi
        
        exit 0
    else
        print_error "Tests failed or were interrupted"
        exit 1
    fi
}

# Handle command line arguments
case "${1:-}" in
    "help" | "-h" | "--help")
        echo "Usage: $0 [test_type] [skip_checks]"
        echo ""
        echo "Test types:"
        echo "  api        - Run API tests only"
        echo "  ui         - Run UI tests only" 
        echo "  workflows  - Run workflow tests only"
        echo "  all        - Run all tests (default)"
        echo ""
        echo "Options:"
        echo "  skip_checks - Skip prerequisite checks (true/false)"
        echo ""
        echo "Environment variables:"
        echo "  CI=true              - Run in CI mode (headless)"
        echo "  HEADLESS=true        - Force headless mode"
        echo "  SKIP_CLEANUP=true    - Skip cleanup after tests"
        echo ""
        echo "Examples:"
        echo "  $0                   # Run all tests"
        echo "  $0 api               # Run API tests only"
        echo "  $0 ui true           # Run UI tests, skip checks"
        echo "  CI=true $0           # Run all tests in headless mode"
        exit 0
        ;;
    *)
        main "$@"
        ;;
esac
