# ORCHESTRATOR Module Map

`erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator` + related tests + SQL.

## Files
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/config/DispatchMappingHealthIndicator.java | configuration and feature wiring for module
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/config/OrchestratorFeatureFlags.java | configuration and feature wiring for module
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/config/SchedulerConfig.java | configuration and feature wiring for module
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/config/ShedLockConfig.java | configuration and feature wiring for module
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/DashboardController.java | REST API entrypoint for module endpoints
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java | REST API entrypoint for module endpoints
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/dto/ApproveOrderRequest.java | request/response DTO definitions
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/dto/DispatchRequest.java | request/response DTO definitions
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/dto/OrderFulfillmentRequest.java | request/response DTO definitions
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/dto/PayrollRunRequest.java | request/response DTO definitions
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/event/DomainEvent.java | event contracts and event persistence models
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/exception/OrchestratorFeatureDisabledException.java | module-specific exception definitions
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/integration/ExternalSyncService.java | integration adapter and external contract wiring
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/policy/PolicyEnforcer.java | policy guard and decision logic
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/AuditRecord.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/AuditRepository.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrchestratorCommand.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrchestratorCommandRepository.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrderAutoApprovalState.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrderAutoApprovalStateRepository.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OutboxEvent.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OutboxEventRepository.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/ScheduledJobDefinition.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/ScheduledJobDefinitionRepository.java | repository layer for module aggregates
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/scheduler/OutboxPublisherJob.java | scheduler wiring and background job behavior
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/scheduler/SchedulerService.java | scheduler wiring and background job behavior
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java | business service logic for module workflows
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/DashboardAggregationService.java | business service logic for module workflows
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/EventPublisherService.java | business service logic for module workflows
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java | business service logic for module workflows
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java | business service logic for module workflows
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrderAutoApprovalListener.java | business service logic for module workflows
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/TraceService.java | business service logic for module workflows
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/workflow/WorkflowService.java | workflow orchestration logic
erp-domain/src/main/resources/db/migration/V101__orchestrator_audit_company_scope.sql | Flyway schema migration file
erp-domain/src/main/resources/db/migration/V117__orchestrator_outbox_company_scope.sql | Flyway schema migration file
erp-domain/src/main/resources/db/migration/V118__orchestrator_command_idempotency.sql | Flyway schema migration file
erp-domain/src/main/resources/db/migration/V121__orchestrator_audit_outbox_identifiers.sql | Flyway schema migration file
erp-domain/src/main/resources/db/migration/V2__orchestrator_tables.sql | Flyway schema migration file
erp-domain/src/main/resources/db/migration_v2/V6__orchestrator.sql | Flyway schema migration file
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/TraceServiceIT.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/event/DomainEventTest.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/policy/PolicyEnforcerTest.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcherTest.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/DashboardAggregationServiceTest.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/EventPublisherServiceTest.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java | module test coverage
erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/OrderAutoApprovalListenerTest.java | module test coverage

## Entrypoint files
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java | Orchestrator API endpoints
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java | Cross-module command dispatch
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java | Integration and handoff coordination

## High-risk files
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java | Exactly-once dispatch contract
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java | Cross-module handoff idempotency
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrchestratorCommandRepository.java | Command dedupe and lifecycle
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/policy/PolicyEnforcer.java | Policy gates for orchestration
erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java | `idempotencyKey` persistence and reuse checks
