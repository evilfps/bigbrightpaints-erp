# Contributing to BigBright ERP

Thank you for your interest in contributing to BigBright ERP! This document provides guidelines and instructions for contributing to the project.

## 🚀 Getting Started

### Prerequisites

Before you begin, ensure you have the following installed:

- **Java 21** (JDK)
- **Maven 3.9+**
- **PostgreSQL 14+**
- **Docker** (for Testcontainers)
- **Git**

### Setting Up Your Development Environment

1. **Fork and Clone the Repository**

```bash
git clone https://github.com/your-username/bigbrightpaints-erp.git
cd bigbrightpaints-erp
```

2. **Set Up the Database**

```bash
# Create PostgreSQL database
createdb bigbright_erp

# Or using Docker
docker-compose up -d postgres
```

3. **Configure Environment Variables**

```bash
# Linux/macOS
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/bigbright_erp
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=your_password
export SPRING_PROFILES_ACTIVE=dev
export JWT_SECRET=your-32-byte-minimum-secret-key-here

# Windows (PowerShell)
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/bigbright_erp"
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="your_password"
$env:SPRING_PROFILES_ACTIVE="dev"
$env:JWT_SECRET="your-32-byte-minimum-secret-key-here"
```

4. **Build and Run Tests**

```bash
cd erp-domain
mvn clean install
```

## 📝 Development Workflow

### 1. Create a Feature Branch

```bash
git checkout -b feature/your-feature-name
```

### 2. Make Your Changes

- Write clean, maintainable code
- Follow existing code style and patterns
- Add tests for new functionality
- Update documentation as needed

### 3. Run Tests Locally

Before committing, ensure all tests pass:

```bash
cd erp-domain

# Run all tests
mvn clean test

# Run tests with coverage
mvn test jacoco:report

# Check code style
mvn checkstyle:check

# Full verification
mvn verify
```

### 4. Commit Your Changes

Follow conventional commit messages:

```bash
git add .
git commit -m "feat: add new feature"
# or
git commit -m "fix: resolve bug in sales module"
# or
git commit -m "docs: update API documentation"
```

**Commit Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `test`: Adding or updating tests
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `chore`: Maintenance tasks

### 5. Push and Create Pull Request

```bash
git push origin feature/your-feature-name
```

Then create a pull request on GitHub.

## 🧪 Testing Guidelines

### Writing Tests

1. **Unit Tests**: Test individual methods and classes
   - Place in `src/test/java` mirroring the source structure
   - Use `@Test` annotation
   - Mock dependencies with Mockito
   - Name pattern: `*Test.java`

2. **Integration Tests**: Test multiple components together
   - Use `@SpringBootTest` for full context
   - Use Testcontainers for database
   - Name pattern: `*IT.java` or `*IntegrationTest.java`

3. **E2E Tests**: Test complete workflows
   - Place in `e2e` package
   - Use realistic scenarios
   - Name pattern: `*E2ETest.java`

### Test Coverage Requirements

- Minimum overall coverage: 20% (enforced by JaCoCo)
- Aim for higher coverage on critical business logic
- All new features should include tests

### Running Specific Tests

```bash
# Run a specific test class
mvn test -Dtest=SalesServiceTest

# Run tests matching a pattern
mvn test -Dtest=*Service*

# Run integration tests only
mvn test -Dtest=*IT
```

## 🔍 Code Quality

### Code Style

- Follow Google Java Style Guide
- Use Checkstyle for validation: `mvn checkstyle:check`
- Keep methods small and focused
- Use meaningful variable names
- Add JavaDoc for public APIs

### Static Analysis

```bash
# Run Checkstyle
mvn checkstyle:check

# Generate Checkstyle report
mvn checkstyle:checkstyle
```

### Best Practices

1. **Services**: Keep business logic in service classes
2. **Controllers**: Handle HTTP concerns only
3. **DTOs**: Use for API contracts
4. **Entities**: Represent database tables
5. **Mappers**: Use MapStruct for conversions
6. **Exceptions**: Use custom exceptions for business errors
7. **Validation**: Use Jakarta Validation annotations
8. **Transactions**: Use `@Transactional` appropriately

## 🚦 CI/CD Pipeline

All pull requests must pass the CI/CD pipeline:

### Automated Checks

1. **Build Verification**: Code must compile successfully
2. **Test Suite**: All tests must pass
3. **Code Quality**: Checkstyle validation must pass
4. **Test Coverage**: Minimum coverage threshold must be met
5. **Database Migrations**: Flyway migrations must be valid

### Pipeline Jobs

- **build-and-test**: Compiles and tests the application
- **code-quality**: Runs static analysis
- **database-migrations**: Validates schema changes

### Viewing CI Results

- Check the "Checks" tab on your pull request
- Download test reports and coverage from artifacts
- Address any failures before requesting review

### Common CI Failures

**Build Failure**
```bash
# Fix locally
mvn clean compile
```

**Test Failure**
```bash
# Run failing tests
mvn test -Dtest=FailingTest
```

**Checkstyle Violation**
```bash
# Check violations
mvn checkstyle:check
# View detailed report
mvn checkstyle:checkstyle
```

**Coverage Below Threshold**
```bash
# Generate coverage report
mvn test jacoco:report
# View in browser
open erp-domain/target/site/jacoco/index.html
```

## 📚 Database Migrations

### Creating Migrations

1. Create migration file in `src/main/resources/db/migration`
2. Follow naming: `V{version}__{description}.sql`
   - Example: `V43__add_customer_table.sql`
3. Write idempotent migrations when possible
4. Test migrations locally

### Testing Migrations

```bash
# Validate migrations
mvn flyway:validate

# Show migration status
mvn flyway:info

# Apply migrations
mvn flyway:migrate

# Undo last migration (if versioned)
mvn flyway:undo
```

## 🔒 Security

- Never commit secrets or credentials
- Use environment variables for sensitive data
- Run security scans before submitting PR
- Report security issues privately

## 📖 Documentation

- Update README.md for user-facing changes
- Update API documentation (OpenAPI/Swagger)
- Add JavaDoc for public APIs
- Update CHANGELOG.md for notable changes

## ❓ Getting Help

- Check existing issues and documentation
- Ask questions in pull request comments
- Review closed issues for similar problems

## 🎯 Pull Request Checklist

Before submitting your pull request, ensure:

- [ ] Code compiles without errors
- [ ] All tests pass locally
- [ ] New tests added for new features
- [ ] Code follows style guidelines
- [ ] Documentation updated
- [ ] Commit messages are clear
- [ ] CI/CD pipeline passes
- [ ] No merge conflicts with main branch

## 📄 License

By contributing, you agree that your contributions will be licensed under the same license as the project (Proprietary License).

---

Thank you for contributing to BigBright ERP! Your efforts help make this project better for everyone.
