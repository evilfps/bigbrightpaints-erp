# Orchestration Layer Governance

Last reviewed: 2026-04-02

## Orchestration Architecture

The orchestrator-erp backend uses an internal orchestrator for background coordination, outbox-based command publishing, and cross-module event dispatch.

### Key Components

- **Outbox publisher** — persists commands to outbox tables before dispatch for reliability.
- **Spring event bridges** — internal events propagate cross-module side effects (e.g., inventory→accounting).
- **Scheduler surfaces** — periodic tasks such as GitHub issue status sync and health monitoring.
- **Retry handling** — failed outbox commands remain available for manual or scheduled retry.

### Orchestration Boundaries

- The orchestrator coordinates but does not own business logic.
- Module services own their domain logic; the orchestrator dispatches commands and tracks outcomes.
- Event listeners bridge modules without creating direct service-to-service dependencies at the orchestrator layer.

### Cross-Module Event Seams

| Source Module | Event | Listener | Target Module |
| --- | --- | --- | --- |
| Inventory | `InventoryMovementEvent` | `InventoryAccountingEventListener` | Accounting |
| Factory | Slip lifecycle events | `FactorySlipEventListener` | Factory/Inventory |
| Admin | Support ticket events | `SupportTicketGitHubSyncService` | GitHub integration |

### Known Limitations

- Not all cross-module event paths have explicit dead-letter handling.
- Event listener error semantics vary by module.
- Some event bridges depend on configuration toggles rather than hard architectural wiring.

## Orchestration Layer Checks

- `bash ci/check-orchestrator-layer.sh` validates orchestrator layer boundaries.
- Changes to `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/` trigger enterprise mode controls.
- The orchestrator layer contract is codified in [agents/orchestrator-layer.yaml](../../agents/orchestrator-layer.yaml).

## Cross-references

- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — full architecture reference
- [docs/RELIABILITY.md](../RELIABILITY.md) — reliability posture
- [docs/agents/ENTERPRISE_MODE.md](ENTERPRISE_MODE.md) — enterprise mode controls
- [docs/agents/WORKFLOW.md](WORKFLOW.md) — workflow governance
