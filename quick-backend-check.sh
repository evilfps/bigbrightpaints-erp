#!/bin/bash

# Quick Backend Check - Simple 30-second verification
# Use this for rapid health assessment

BACKEND_URL=${BACKEND_URL:-"http://localhost:8080"}
ADMIN_EMAIL=${ADMIN_EMAIL:-"admin@bbp.dev"}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-"ChangeMe123!"}

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}🚀 Quick Backend Health Check${NC}"
echo "Target: $BACKEND_URL"
echo "================================"

# 1. Basic connectivity
echo -n "1. Backend connectivity... "
if curl -s -f "$BACKEND_URL/actuator/health" | grep -q '"status":"UP"'; then
    echo -e "${GREEN}✅ OK${NC}"
else
    echo -e "${RED}❌ FAILED${NC}"
    exit 1
fi

# 2. Admin login
echo -n "2. Admin authentication... "
LOGIN_RESPONSE=$(curl -s -X POST "$BACKEND_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")

if echo "$LOGIN_RESPONSE" | grep -q '"success":true'; then
    echo -e "${GREEN}✅ OK${NC}"
    TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
else
    echo -e "${RED}❌ FAILED${NC}"
    exit 1
fi

# 3. Database via API
echo -n "3. Database connectivity... "
if curl -s -H "Authorization: Bearer $TOKEN" "$BACKEND_URL/api/v1/sales/dealers" | grep -q '"success":true'; then
    echo -e "${GREEN}✅ OK${NC}"
else
    echo -e "${RED}❌ FAILED${NC}"
    exit 1
fi

# 4. Core modules
echo -n "4. Core modules... "
modules_ok=true
for endpoint in "sales/dealers" "accounting/accounts" "inventory/finished-goods"; do
    if ! curl -s -H "Authorization: Bearer $TOKEN" "$BACKEND_URL/api/v1/$endpoint" | grep -q '"success":true'; then
        modules_ok=false
        break
    fi
done

if $modules_ok; then
    echo -e "${GREEN}✅ OK${NC}"
else
    echo -e "${YELLOW}⚠️  Some issues${NC}"
fi

echo "================================"
echo -e "${GREEN}✅ Backend is functional!${NC}"
echo ""
echo "For detailed check, run:"
echo "  node cypress-e2e-tests/backend-health-check.js"
echo "  ./check-backend.sh"
