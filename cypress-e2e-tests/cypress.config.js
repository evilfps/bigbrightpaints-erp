const { defineConfig } = require('cypress')

module.exports = defineConfig({
  e2e: {
    baseUrl: 'http://localhost:3002', // Your React frontend
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
      // Backend API base URL
      apiUrl: 'http://localhost:8080',
      // Test credentials
      adminEmail: 'admin@bbp.dev',
      adminPassword: 'ChangeMe123!',
      // Test company
      companyCode: 'BBP',
      // Database connection (for test data setup/cleanup)
      dbHost: 'localhost',
      dbPort: 5432,
      dbName: 'erp_domain',
      dbUser: 'erp',
      dbPassword: 'erp'
    },
    setupNodeEvents(on, config) {
      // Task for database operations
      on('task', {
        queryDb: require('./cypress/plugins/db-tasks'),
        seedTestData: require('./cypress/plugins/seed-tasks'),
        cleanupTestData: require('./cypress/plugins/cleanup-tasks')
      })
    }
  }
})
