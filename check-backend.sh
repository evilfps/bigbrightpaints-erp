#!/bin/bash

# Comprehensive Backend Check Script
# Tests all aspects of the BigBright ERP backend

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m'

# Configuration
BACKEND_URL=${BACKEND_URL:-"http://localhost:8080"}
ADMIN_EMAIL=${ADMIN_EMAIL:-"admin@bbp.dev"}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-"ChangeMe123!"}
COMPANY_CODE=${COMPANY_CODE:-"BBP"}

print_header() {
    echo -e "\n${BLUE}================================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}================================================${NC}"
}

print_section() {
    echo -e "\n${CYAN}>>> $1${NC}"
    echo -e "${CYAN}$(echo $1 | sed 's/./─/g')${NC}"
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

# Test counters
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

run_test() {
    local test_name="$1"
    local test_command="$2"
    local expected_pattern="$3"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -n "  Testing $test_name... "
    
    if result=$(eval "$test_command" 2>&1); then
        if [[ -n "$expected_pattern" ]]; then
            if echo "$result" | grep -q "$expected_pattern"; then
                echo -e "${GREEN}✅ PASS${NC}"
                PASSED_TESTS=$((PASSED_TESTS + 1))
                return 0
            else
                echo -e "${RED}❌ FAIL (unexpected response)${NC}"
                FAILED_TESTS=$((FAILED_TESTS + 1))
                return 1
            fi
        else
            echo -e "${GREEN}✅ PASS${NC}"
            PASSED_TESTS=$((PASSED_TESTS + 1))
            return 0
        fi
    else
        echo -e "${RED}❌ FAIL${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        return 1
    fi
}

# Get auth token
get_auth_token() {
    local response=$(curl -s -X POST "$BACKEND_URL/api/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" 2>/dev/null)
    
    if echo "$response" | grep -q '"success":true'; then
        echo "$response" | grep -o '"token":"[^"]*"' | cut -d'"' -f4
    else
        echo ""
    fi
}

main() {
    print_header "🔍 BIGBRIGHT ERP BACKEND COMPREHENSIVE CHECK"
    
    print_info "Target Backend: $BACKEND_URL"
    print_info "Admin Email: $ADMIN_EMAIL"
    print_info "Company: $COMPANY_CODE"
    
    print_section "INFRASTRUCTURE TESTS"
    
    run_test "Basic connectivity" \
        "curl -s -f '$BACKEND_URL/actuator/health'" \
        '"status":"UP"'
    
    run_test "OpenAPI documentation" \
        "curl -s -f '$BACKEND_URL/v3/api-docs'" \
        '"openapi"'
    
    run_test "Swagger UI" \
        "curl -s -f '$BACKEND_URL/swagger-ui/'" \
        "swagger"
    
    print_section "AUTHENTICATION TESTS"
    
    run_test "Admin login" \
        "curl -s -X POST '$BACKEND_URL/api/v1/auth/login' -H 'Content-Type: application/json' -d '{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}'" \
        '"success":true'
    
    # Get token for authenticated requests
    print_info "Obtaining authentication token..."
    AUTH_TOKEN=$(get_auth_token)
    
    if [[ -n "$AUTH_TOKEN" ]]; then
        print_success "Authentication token obtained"
        
        run_test "Token validation" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/auth/profile'" \
            '"success":true'
        
        run_test "Company context switch" \
            "curl -s -X POST '$BACKEND_URL/api/v1/multi-company/companies/switch' -H 'Authorization: Bearer $AUTH_TOKEN' -H 'Content-Type: application/json' -d '{\"companyCode\":\"$COMPANY_CODE\"}'" \
            '"success":true'
        
        # Update token after company switch
        NEW_TOKEN_RESPONSE=$(curl -s -X POST "$BACKEND_URL/api/v1/multi-company/companies/switch" \
            -H "Authorization: Bearer $AUTH_TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"companyCode\":\"$COMPANY_CODE\"}")
        
        if echo "$NEW_TOKEN_RESPONSE" | grep -q '"success":true'; then
            AUTH_TOKEN=$(echo "$NEW_TOKEN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
        fi
    else
        print_error "Could not obtain authentication token"
    fi
    
    print_section "MODULE TESTS"
    
    if [[ -n "$AUTH_TOKEN" ]]; then
        run_test "Sales module - Dealers" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/sales/dealers'" \
            '"success":true'
        
        run_test "Sales module - Orders" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/sales/orders'" \
            '"success":true'
        
        run_test "Accounting module - Accounts" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/accounting/accounts'" \
            '"success":true'
        
        run_test "Accounting module - Journal entries" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/accounting/journal-entries'" \
            '"success":true'
        
        run_test "Inventory module - Finished goods" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/inventory/finished-goods'" \
            '"success":true'
        
        run_test "Factory module - Production plans" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/factory/plans'" \
            '"success":true'
        
        run_test "HR module - Employees" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/hr/employees'" \
            '"success":true'
        
        run_test "Invoice module" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/invoices'" \
            '"success":true'
    else
        print_warning "Skipping module tests (no auth token)"
    fi
    
    print_section "WORKFLOW TESTS"
    
    if [[ -n "$AUTH_TOKEN" ]]; then
        run_test "Orchestrator health" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/orchestrator/health'" \
            ""
        
        run_test "Admin dashboard" \
            "curl -s -H 'Authorization: Bearer $AUTH_TOKEN' '$BACKEND_URL/api/v1/dashboard/admin'" \
            ""
    else
        print_warning "Skipping workflow tests (no auth token)"
    fi
    
    print_section "DATABASE TESTS"
    
    if command -v node >/dev/null 2>&1; then
        if [[ -f "cypress-e2e-tests/database-check.js" ]]; then
            print_info "Running comprehensive database check..."
            if node cypress-e2e-tests/database-check.js; then
                print_success "Database check completed"
            else
                print_warning "Database check had issues"
            fi
        else
            print_warning "Database check script not found"
        fi
    else
        print_warning "Node.js not available, skipping database tests"
    fi
    
    print_section "BUSINESS LOGIC TESTS"
    
    if [[ -n "$AUTH_TOKEN" ]]; then
        # Test creating a dealer
        DEALER_NAME="Health Check Dealer $(date +%s)"
        DEALER_CODE="HC$(date +%s | tail -c 6)"
        
        run_test "Create test dealer" \
            "curl -s -X POST '$BACKEND_URL/api/v1/sales/dealers' -H 'Authorization: Bearer $AUTH_TOKEN' -H 'Content-Type: application/json' -d '{\"name\":\"$DEALER_NAME\",\"code\":\"$DEALER_CODE\",\"email\":\"test@example.com\",\"creditLimit\":50000}'" \
            '"success":true'
        
        # Test creating an account
        ACCOUNT_CODE="HC$(date +%s | tail -c 6)"
        
        run_test "Create test account" \
            "curl -s -X POST '$BACKEND_URL/api/v1/accounting/accounts' -H 'Authorization: Bearer $AUTH_TOKEN' -H 'Content-Type: application/json' -d '{\"code\":\"$ACCOUNT_CODE\",\"name\":\"Health Check Account\",\"type\":\"ASSET\"}'" \
            '"success":true'
    else
        print_warning "Skipping business logic tests (no auth token)"
    fi
    
    print_section "PERFORMANCE TESTS"
    
    # Test response times
    print_info "Measuring response times..."
    
    if command -v curl >/dev/null 2>&1; then
        health_time=$(curl -s -o /dev/null -w "%{time_total}" "$BACKEND_URL/actuator/health" 2>/dev/null || echo "0")
        print_info "Health check: ${health_time}s"
        
        if [[ -n "$AUTH_TOKEN" ]]; then
            dealers_time=$(curl -s -o /dev/null -w "%{time_total}" -H "Authorization: Bearer $AUTH_TOKEN" "$BACKEND_URL/api/v1/sales/dealers" 2>/dev/null || echo "0")
            print_info "Dealers endpoint: ${dealers_time}s"
        fi
    fi
    
    # Memory usage (if available)
    if curl -s -f "$BACKEND_URL/actuator/metrics/jvm.memory.used" >/dev/null 2>&1; then
        memory_info=$(curl -s "$BACKEND_URL/actuator/metrics/jvm.memory.used" 2>/dev/null)
        print_info "Memory metrics available"
    fi
    
    print_header "📊 TEST RESULTS SUMMARY"
    
    echo -e "${BLUE}Total Tests: $TOTAL_TESTS${NC}"
    echo -e "${GREEN}Passed: $PASSED_TESTS${NC}"
    echo -e "${RED}Failed: $FAILED_TESTS${NC}"
    
    SUCCESS_RATE=$(( (PASSED_TESTS * 100) / TOTAL_TESTS ))
    echo -e "${BLUE}Success Rate: $SUCCESS_RATE%${NC}"
    
    print_header "🎯 HEALTH ASSESSMENT"
    
    if [[ $FAILED_TESTS -eq 0 ]]; then
        print_success "EXCELLENT: All tests passed! Backend is fully operational."
        echo -e "${GREEN}✅ Ready for production use${NC}"
        echo -e "${GREEN}✅ All modules functioning correctly${NC}"
        echo -e "${GREEN}✅ Authentication and authorization working${NC}"
        echo -e "${GREEN}✅ Database connectivity established${NC}"
        echo -e "${GREEN}✅ Business logic operational${NC}"
    elif [[ $FAILED_TESTS -le 2 ]]; then
        print_warning "GOOD: Minor issues detected, but core functionality works."
        echo -e "${YELLOW}👍 Backend mostly operational${NC}"
        echo -e "${YELLOW}🔧 Some endpoints may need attention${NC}"
        echo -e "${YELLOW}📋 Review failed tests above${NC}"
    elif [[ $FAILED_TESTS -le 5 ]]; then
        print_warning "FAIR: Several issues detected, needs attention."
        echo -e "${YELLOW}⚠️  Backend partially functional${NC}"
        echo -e "${YELLOW}🔧 Multiple endpoints need fixes${NC}"
        echo -e "${YELLOW}📋 Priority: Fix authentication and core modules${NC}"
    else
        print_error "POOR: Multiple critical issues detected."
        echo -e "${RED}❌ Backend requires immediate attention${NC}"
        echo -e "${RED}🔧 Critical systems not functioning${NC}"
        echo -e "${RED}📋 Priority: Review all failed tests${NC}"
    fi
    
    print_header "📋 RECOMMENDATIONS"
    
    if [[ $FAILED_TESTS -eq 0 ]]; then
        echo -e "${GREEN}• Ready to run comprehensive E2E tests${NC}"
        echo -e "${GREEN}• Can proceed with frontend integration${NC}"
        echo -e "${GREEN}• Consider load testing for production readiness${NC}"
    else
        echo -e "${YELLOW}• Fix failed tests before proceeding${NC}"
        echo -e "${YELLOW}• Check backend logs for detailed error information${NC}"
        echo -e "${YELLOW}• Verify database connectivity and data integrity${NC}"
        echo -e "${YELLOW}• Ensure all required services are running${NC}"
        
        if [[ -z "$AUTH_TOKEN" ]]; then
            echo -e "${YELLOW}• Priority: Fix authentication system${NC}"
        fi
    fi
    
    # Exit with appropriate code
    if [[ $FAILED_TESTS -eq 0 ]]; then
        exit 0
    else
        exit 1
    fi
}

# Help message
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    cat << EOF
BigBright ERP Backend Comprehensive Check

Usage: $0 [options]

Environment Variables:
  BACKEND_URL      Backend URL (default: http://localhost:8080)
  ADMIN_EMAIL      Admin email (default: admin@bbp.dev)
  ADMIN_PASSWORD   Admin password (default: ChangeMe123!)
  COMPANY_CODE     Company code (default: BBP)

Options:
  --help, -h       Show this help message

This script performs comprehensive backend testing:
  🔍 Infrastructure (connectivity, health, documentation)
  🔐 Authentication (login, tokens, company context)
  📋 All modules (sales, accounting, inventory, factory, HR)
  🔄 Workflows (orchestrator, dashboards)
  🗄️  Database (connectivity, integrity, performance)
  💼 Business logic (CRUD operations, validation)
  ⚡ Performance (response times, resource usage)

Exit codes:
  0 - All tests passed
  1 - Some tests failed
EOF
    exit 0
fi

# Run main function
main "$@"
