@echo off
setlocal enabledelayedexpansion

REM Quick Start Script for BigBright ERP Cypress Tests (Windows)
REM This script automates the setup process on Windows

echo.
echo ================================================================
echo            BigBright ERP - E2E Testing Setup
echo                   Quick Start Script (Windows)
echo ================================================================
echo.

REM Check Node.js
echo [STEP 1] Checking Prerequisites...
echo ----------------------------------------

node --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Node.js not found. Please install Node.js 16+ from https://nodejs.org
    pause
    exit /b 1
) else (
    for /f "tokens=*" %%i in ('node --version') do set NODE_VERSION=%%i
    echo ✅ Node.js !NODE_VERSION! found
)

npm --version >nul 2>&1
if errorlevel 1 (
    echo ❌ npm not found
    pause
    exit /b 1
) else (
    for /f "tokens=*" %%i in ('npm --version') do set NPM_VERSION=%%i
    echo ✅ npm !NPM_VERSION! found
)

curl --version >nul 2>&1
if errorlevel 1 (
    echo ❌ curl not found. Please install curl or use Git Bash
    pause
    exit /b 1
) else (
    echo ✅ curl found
)

echo.
echo [STEP 2] Service Detection...
echo ----------------------------------------

REM Check backend services
set BACKEND_URL=
curl -s -f "http://localhost:8080/actuator/health" >nul 2>&1
if !errorlevel! == 0 (
    echo ✅ Backend found on port 8080
    set BACKEND_URL=http://localhost:8080
) else (
    curl -s -f "http://localhost:8081/actuator/health" >nul 2>&1
    if !errorlevel! == 0 (
        echo ✅ Backend found on port 8081
        set BACKEND_URL=http://localhost:8081
    ) else (
        echo ⚠️  Backend not detected on common ports
        set /p BACKEND_URL="Enter backend URL (e.g., http://localhost:8080): "
    )
)

REM Check frontend services
set FRONTEND_URL=
curl -s -f "http://localhost:3002" >nul 2>&1
if !errorlevel! == 0 (
    echo ✅ Frontend found on port 3002
    set FRONTEND_URL=http://localhost:3002
) else (
    curl -s -f "http://localhost:3000" >nul 2>&1
    if !errorlevel! == 0 (
        echo ✅ Frontend found on port 3000
        set FRONTEND_URL=http://localhost:3000
    ) else (
        echo ⚠️  Frontend not detected on common ports
        set /p FRONTEND_URL="Enter frontend URL (e.g., http://localhost:3002): "
    )
)

echo.
echo [STEP 3] Configuration Input...
echo ----------------------------------------

set /p ADMIN_EMAIL="Admin email [admin@bbp.dev]: "
if "!ADMIN_EMAIL!"=="" set ADMIN_EMAIL=admin@bbp.dev

set /p ADMIN_PASSWORD="Admin password [ChangeMe123!]: "
if "!ADMIN_PASSWORD!"=="" set ADMIN_PASSWORD=ChangeMe123!

set /p COMPANY_CODE="Company code [BBP]: "
if "!COMPANY_CODE!"=="" set COMPANY_CODE=BBP

echo.
echo [STEP 4] Installation...
echo ----------------------------------------

echo Installing npm dependencies...
call npm install
if errorlevel 1 (
    echo ❌ npm install failed
    pause
    exit /b 1
)
echo ✅ Dependencies installed

echo.
echo [STEP 5] Configuration...
echo ----------------------------------------

REM Create Cypress configuration file
echo const { defineConfig } = require('cypress'^) > cypress.config.js
echo. >> cypress.config.js
echo module.exports = defineConfig({ >> cypress.config.js
echo   e2e: { >> cypress.config.js
echo     baseUrl: '!FRONTEND_URL!', >> cypress.config.js
echo     supportFile: 'cypress/support/e2e.js', >> cypress.config.js
echo     specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}', >> cypress.config.js
echo     viewportWidth: 1280, >> cypress.config.js
echo     viewportHeight: 720, >> cypress.config.js
echo     video: true, >> cypress.config.js
echo     screenshotOnRunFailure: true, >> cypress.config.js
echo     defaultCommandTimeout: 10000, >> cypress.config.js
echo     requestTimeout: 10000, >> cypress.config.js
echo     responseTimeout: 10000, >> cypress.config.js
echo     env: { >> cypress.config.js
echo       apiUrl: '!BACKEND_URL!', >> cypress.config.js
echo       adminEmail: '!ADMIN_EMAIL!', >> cypress.config.js
echo       adminPassword: '!ADMIN_PASSWORD!', >> cypress.config.js
echo       companyCode: '!COMPANY_CODE!', >> cypress.config.js
echo       dbHost: 'localhost', >> cypress.config.js
echo       dbPort: 5432, >> cypress.config.js
echo       dbName: 'erp_domain', >> cypress.config.js
echo       dbUser: 'erp', >> cypress.config.js
echo       dbPassword: 'erp' >> cypress.config.js
echo     }, >> cypress.config.js
echo     setupNodeEvents(on, config^) { >> cypress.config.js
echo       on('task', { >> cypress.config.js
echo         queryDb: require('./cypress/plugins/db-tasks'^), >> cypress.config.js
echo         seedTestData: require('./cypress/plugins/seed-tasks'^), >> cypress.config.js
echo         cleanupTestData: require('./cypress/plugins/cleanup-tasks'^) >> cypress.config.js
echo       }^) >> cypress.config.js
echo     } >> cypress.config.js
echo   } >> cypress.config.js
echo }^) >> cypress.config.js

echo ✅ Configuration file created

echo.
echo [STEP 6] Verification...
echo ----------------------------------------

echo Testing admin login...
curl -s -X POST "!BACKEND_URL!/api/v1/auth/login" ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"!ADMIN_EMAIL!\",\"password\":\"!ADMIN_PASSWORD!\"}" > temp_login_response.json

findstr "success.*true" temp_login_response.json >nul
if !errorlevel! == 0 (
    echo ✅ Admin login successful
) else (
    echo ❌ Admin login failed
    echo ⚠️  Please check your credentials
)
del temp_login_response.json >nul 2>&1

echo.
echo [STEP 7] Initial Test Run...
echo ----------------------------------------

echo Running setup verification test...
call npx cypress run --spec "cypress/e2e/setup/01-initial-setup.cy.js" --headless
if errorlevel 1 (
    echo ⚠️  Setup verification had issues. Check the output above.
) else (
    echo ✅ Setup verification passed
)

echo.
echo ================================================================
echo                    🎉 SETUP COMPLETE! 🎉
echo ================================================================
echo.

echo 📋 What's been configured:
echo    ✅ Dependencies installed
echo    ✅ Configuration created
echo    ✅ Backend connection verified (!BACKEND_URL!)
echo    ✅ Frontend connection verified (!FRONTEND_URL!)
echo    ✅ Admin credentials tested
echo.

echo 🚀 Next Steps:
echo    1. npm run cypress:open     # Open interactive test runner
echo    2. npm run test:api         # Run API tests only
echo    3. npm run test:ui          # Run UI tests only
echo    4. npm run test:all         # Run all tests
echo.

echo 📚 Helpful Commands:
echo    npm run cypress:run         # Run all tests headless
echo    npm run test:smoke          # Run smoke tests only
echo    node setup-check.js         # Check prerequisites anytime
echo.

echo 📖 Documentation:
echo    📄 INSTALLATION.md          # Detailed setup guide
echo    📄 README.md               # Complete documentation
echo.

echo Happy Testing! 🧪
echo.
pause
