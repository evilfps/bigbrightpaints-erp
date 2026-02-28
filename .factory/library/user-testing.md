# User Testing

Testing surface: tools, URLs, setup steps, isolation notes, known quirks.

**What belongs here:** How to manually test the application, testing tools, setup steps, known issues.

---

## Testing Surface
- **Type**: REST API (no frontend in this mission)
- **Base URL**: http://localhost:8081
- **Swagger UI**: http://localhost:8081/swagger-ui (dev profile only)
- **Actuator**: http://localhost:9090/actuator (management port)
- **MailHog UI**: http://localhost:8025 (email testing)

## Testing Tools
- `curl` for API endpoint testing
- Docker Compose for full stack (postgres, rabbitmq, mailhog, app)
- Testcontainers for integration tests (auto-managed PostgreSQL)

## Setup Steps
1. Ensure .env file exists (init.sh creates from .env.example)
2. Start services: `docker compose up -d`
3. Wait for health: `curl -sf http://localhost:8081/actuator/health`
4. Auth: POST /api/v1/auth/login with credentials to get JWT token
5. Use token in Authorization: Bearer header for protected endpoints

## Known Issues
- 1 pre-existing test failure: `TS_RuntimeTenantPolicyControlExecutableCoverageTest`
- Tests use H2 for unit tests, Testcontainers PostgreSQL for integration tests
- RabbitMQ health check excluded in dev profile
