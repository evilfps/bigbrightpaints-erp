# 🏭 BigBright ERP

[![ERP Domain CI/CD](https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/actions/workflows/erp-domain-ci.yml/badge.svg)](https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/actions/workflows/erp-domain-ci.yml)
[![Java Version](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)](LICENSE.md)

A comprehensive Enterprise Resource Planning (ERP) system designed for manufacturing businesses and enterprises. This full-stack application manages the complete business lifecycle including sales, inventory, production, accounting, HR, and dealer management.

## 📋 Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [API Documentation](#api-documentation)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [License](#license)

## Overview

BigBright ERP is a modular, enterprise-grade system built to streamline manufacturing and enterprise operations. It provides end-to-end visibility across:

- **Sales & Distribution** – Dealer management, order processing, and invoicing
- **Manufacturing** – Production planning, batch tracking, and packaging
- **Inventory** – Raw materials, finished goods, and stock management
- **Finance & Accounting** – General ledger, journal entries, GST returns, and financial reporting
- **HR & Payroll** – Employee management, leave requests, and payroll processing

## ✨ Features

### 🛒 Sales Management
- Dealer registration and credit management
- Sales order creation with multi-line items
- Promotions and discount management
- Order-to-cash workflow automation
- Dealer portal for self-service

### 🏭 Production & Factory
- Production planning and scheduling
- Batch tracking and cost allocation
- Packaging size mappings
- Factory task management
- Real-time production logs

### 📦 Inventory Management
- Raw material intake and tracking
- Finished goods with batch management
- Low stock alerts and reorder points
- Inventory adjustments and valuations
- FIFO/weighted average costing

### 💰 Accounting & Finance
- Chart of accounts with hierarchical structure
- Journal entries with automatic reversals
- Multi-period accounting with lock/close
- Bank reconciliation
- Financial reports (Trial Balance, P&L, Balance Sheet, Cash Flow)
- GST/Tax return generation
- Dealer/Supplier aging reports

### 👥 HR & Payroll
- Employee management
- Leave request workflows
- Payroll runs with batch payments
- Attendance tracking

### 🔐 Security & Access
- JWT-based authentication with refresh tokens
- Multi-factor authentication (MFA/TOTP)
- Role-based access control (RBAC)
- Multi-company support with company switching

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Frontend Clients                          │
│              (React/TypeScript via generated API client)         │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                     REST API (OpenAPI 3.0)                       │
│                      Port: 8081 (default)                        │
└────────────��────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Spring Boot Backend (Java 21)                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐   │
│  │  Sales   │ │ Factory  │ │Accounting│ │   Orchestrator   │   │
│  │ Module   │ │  Module  │ │  Module  │ │     (Workflow)   │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      PostgreSQL Database                         │
│                   (Flyway migrations)                            │
└─────────────────────────────────────────────────────────────────┘
```

## 📋 Prerequisites

- **Java 21** (JDK)
- **Maven 3.9+**
- **PostgreSQL 14+**
- **Docker** (for Testcontainers/integration tests)
- **Node.js 16+** (for API client generation and E2E tests)

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/anasibnanwar-XYE/bigbrightpaints-erp.git
cd bigbrightpaints-erp
```

### 2. Set Up the Database

```bash
# Create PostgreSQL database
createdb bigbright_erp

# Or using Docker
docker-compose up -d postgres
```

### 3. Configure Environment Variables

```bash
# Windows (CMD)
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/bigbright_erp
set SPRING_DATASOURCE_USERNAME=postgres
set SPRING_DATASOURCE_PASSWORD=your_password
set SPRING_PROFILES_ACTIVE=dev
set JWT_SECRET=your-32-byte-minimum-secret-key-here

# Linux/macOS
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/bigbright_erp
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=your_password
export SPRING_PROFILES_ACTIVE=dev
export JWT_SECRET=your-32-byte-minimum-secret-key-here
```

### 4. Run the Application

```bash
cd erp-domain
mvn spring-boot:run
```

The API will be available at `http://localhost:8081`

### 5. Access the API Documentation

- **Swagger UI**: `http://localhost:8081/swagger-ui.html`
- **OpenAPI Spec**: `http://localhost:8081/v3/api-docs`

## ⚙️ Configuration

### Critical Configuration

| Variable | Description | Required |
|----------|-------------|----------|
| `JWT_SECRET` | JWT signing key (minimum 32 bytes) | ✅ |
| `SPRING_DATASOURCE_URL` | PostgreSQL connection URL | ✅ |
| `SPRING_DATASOURCE_USERNAME` | Database username | ✅ |
| `SPRING_DATASOURCE_PASSWORD` | Database password | ✅ |
| `ERP_DISPATCH_DEBIT_ACCOUNT_ID` | Account ID for dispatch debit postings | Optional |
| `ERP_DISPATCH_CREDIT_ACCOUNT_ID` | Account ID for dispatch credit postings | Optional |

### Profiles

- **`dev`** – Development profile with fixture seeding (periods, GL accounts, dealer/supplier, FG stock)
- **`prod`** – Production profile with stricter security
- **`test`** – Testing profile with Testcontainers

### Flyway Migrations

If you encounter migration gaps (e.g., V42 placeholder):

```bash
mvn -f erp-domain/pom.xml -DskipTests \
  "-Dflyway.url=jdbc:postgresql://localhost:5432/bigbright_erp" \
  "-Dflyway.user=postgres" \
  "-Dflyway.password=your_password" \
  "-Dflyway.outOfOrder=true" flyway:migrate
```

## 📚 API Documentation

### Core API Modules

| Module | Base Path | Description |
|--------|-----------|-------------|
| **Auth** | `/api/v1/auth` | Login, logout, MFA, password management |
| **Sales** | `/api/v1/sales` | Orders, promotions, targets, credit requests |
| **Dealers** | `/api/v1/dealers` | Dealer management, aging, ledger |
| **Accounting** | `/api/v1/accounting` | Accounts, journal entries, periods, reports |
| **Factory** | `/api/v1/factory` | Production plans, batches, tasks, packing |
| **Inventory** | `/api/v1/finished-goods` | Finished goods, stock, batches |
| **Raw Materials** | `/api/v1/raw-materials` | Stock, intake, low-stock alerts |
| **HR** | `/api/v1/hr` | Employees, leave requests, payroll |
| **Reports** | `/api/v1/reports` | Financial and operational reports |
| **Admin** | `/api/v1/admin` | Users, roles, permissions |

### Generated API Client

A TypeScript/Axios client is available in `clients/typescript-axios/`:

```bash
cd clients/typescript-axios
npm install
npm run build
```

To regenerate from OpenAPI spec:

```bash
npx @openapitools/openapi-generator-cli generate \
  -i openapi.yaml \
  -g typescript-axios \
  -o clients/typescript-axios
```

## 🧪 Testing

### Unit & Integration Tests

```bash
# Run all tests
cd erp-domain
mvn test

# Run tests with coverage
mvn test jacoco:report

# Build without tests
mvn -q -DskipTests package
```

**Test Coverage**: The project includes 155+ tests covering:
- Unit tests for services and business logic
- Integration tests with Testcontainers
- End-to-end workflow tests
- Regression tests for critical business processes

Coverage reports are generated in `erp-domain/target/site/jacoco/`.

### E2E Tests (Cypress)

```bash
cd cypress-e2e-tests
npm install

# Interactive mode
npm run cypress:open

# Headless mode
npm run cypress:run

# Specific test suites
npm run test:api       # API tests
npm run test:ui        # UI tests
npm run test:workflows # Workflow tests
npm run test:smoke     # Smoke tests
```

### Cloud/Testcontainers

```bash
scripts/run-tests-cloud.sh
```

See `docs/CLOUD_CONTAINERS.md` for Docker-in-Docker setup.

## 🔄 CI/CD Pipeline

The project uses GitHub Actions for continuous integration and deployment:

### Automated Checks

1. **Build and Test** - Compiles code and runs full test suite
2. **Code Quality** - Runs Checkstyle for code style validation
3. **Database Migrations** - Validates Flyway migrations against PostgreSQL
4. **Test Coverage** - Generates JaCoCo coverage reports (minimum 20% required)
5. **Artifact Publishing** - Archives build artifacts and test reports

### Pipeline Jobs

- **build-and-test**: Builds the application, runs tests, generates coverage reports
- **code-quality**: Runs static code analysis and verification
- **database-migrations**: Validates database schema changes

### Viewing Results

- Test results are uploaded as artifacts for each workflow run
- Coverage reports are available in the workflow artifacts
- Build artifacts (.jar files) are retained for 7 days

### Running CI Locally

```bash
# Run the same checks locally
cd erp-domain

# Build and test
mvn clean test jacoco:report

# Code quality
mvn checkstyle:check

# Verify (full build with checks)
mvn verify
```

## 📁 Project Structure

```
bigbrightpaints-erp/
├── erp-domain/                    # Main Spring Boot backend
│   ├── src/
│   │   ├── main/java/            # Application source code
│   │   └── main/resources/       # Configuration & migrations
│   ├── pom.xml                   # Maven configuration
│   ├── Dockerfile                # Container build file
│   └── openapi.yaml              # OpenAPI specification
│
├── tally-ingestion-backend/       # Tally data ingestion service
│   ├── flyway-migrations/        # Database migrations
│   └── src/                      # Ingestion logic
│
├── clients/
│   └── typescript-axios/          # Generated TypeScript API client
│
├── cypress-e2e-tests/             # End-to-end test suite
│   ├── cypress/
│   │   ├── e2e/                  # Test specifications
│   │   ├── support/              # Custom commands
│   │   └── plugins/              # DB tasks & seeding
│   └── cypress.config.js
│
├── cloud/                         # Cloud deployment configs
├── docs/                          # Additional documentation
├── scripts/                       # Utility scripts
├── docker-compose.yml             # Local development setup
└── openapi.yaml                   # Root OpenAPI specification
```

## 📄 License

Copyright © 2024-2025 Anas Ibn Anwar. All Rights Reserved.

This software is protected under a proprietary license. See [LICENSE.md](LICENSE.md) for full terms.

**Viewing access does not grant any rights to use, copy, modify, or distribute this software.**

For licensing inquiries or partnership discussions, contact the copyright holder directly.

---

<p align="center">
  <strong>BigBright ERP</strong> — Enterprise-Grade Manufacturing & Business Management
</p>
