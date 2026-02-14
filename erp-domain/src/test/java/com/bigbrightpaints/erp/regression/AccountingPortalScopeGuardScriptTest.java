package com.bigbrightpaints.erp.regression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingPortalScopeGuardScriptTest {

    @TempDir
    Path tempDir;

    @Test
    void guardPassesWhenScopeFixtureKeepsAllDomainMappings() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).contains("[guard_accounting_portal_scope_contract] OK");
    }

    @Test
    void guardFailsWhenReportsModuleCountDriftsToZero() throws Exception {
        FixturePaths fixturePaths = writeFixture(0);

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("non-zero path count: reports");
    }

    @Test
    void guardFailsWhenHrModuleCountDriftsToZero() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.endpointInventoryDoc(),
                "| `hr` | 11 | /api/v1/hr/employees |",
                "| `hr` | 0 | /api/v1/hr/employees |");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("non-zero path count: hr");
    }

    @Test
    void guardFailsWhenEndpointMapDomainHeadingIsMissing() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.endpointMapDoc(),
                "## Reports & Reconciliation\n",
                "");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("accounting endpoint map missing required domain heading");
        assertThat(result.stderr()).contains("## Reports & Reconciliation");
    }

    @Test
    void guardFailsWhenHandoffDomainHeadingIsMissing() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.handoffDoc(),
                "## Reports & Reconciliation\n",
                "");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("accounting frontend handoff missing required domain heading");
        assertThat(result.stderr()).contains("## Reports & Reconciliation");
    }

    @Test
    void guardFailsWhenEndpointMapControllerSectionIsMissing() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.endpointMapDoc(),
                "### report-controller\n",
                "");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("accounting endpoint map missing required controller section");
        assertThat(result.stderr()).contains("### report-controller");
    }

    @Test
    void guardFailsWhenEndpointMapHrControllerSectionIsMissing() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.endpointMapDoc(),
                "### hr-controller\n",
                "");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("accounting endpoint map missing required controller section");
        assertThat(result.stderr()).contains("### hr-controller");
    }

    @Test
    void guardFailsWhenEndpointMapInventoryEvidenceIsMissing() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.endpointMapDoc(),
                "| `GET /api/v1/finished-goods/stock-summary` |\n",
                "");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("required inventory endpoint evidence missing");
        assertThat(result.stderr()).contains("/api/v1/finished-goods/stock-summary");
    }

    @Test
    void guardFailsWhenHandoffHrEndpointEvidenceIsMissing() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.handoffDoc(),
                "| `hrEmployees` | GET | `/api/v1/hr/employees` |\n",
                "");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("required hr endpoint evidence missing");
        assertThat(result.stderr()).contains("/api/v1/hr/employees");
    }

    @Test
    void guardFailsWhenEndpointInventoryReportsEvidenceIsMissing() throws Exception {
        FixturePaths fixturePaths = writeFixture(13);
        replaceInFile(
                fixturePaths.endpointInventoryDoc(),
                "- `GET` `/api/v1/reports/inventory-valuation`\n",
                "");

        ProcessResult result = runGuard(fixturePaths);

        assertThat(result.exitCode()).isNotEqualTo(0);
        assertThat(result.stderr()).contains("required reports endpoint evidence missing");
        assertThat(result.stderr()).contains("/api/v1/reports/inventory-valuation");
    }

    private ProcessResult runGuard(FixturePaths fixturePaths) throws Exception {
        Path root = repoRoot();
        Path script = root.resolve("scripts/guard_accounting_portal_scope_contract.sh");

        ProcessBuilder processBuilder = new ProcessBuilder("bash", script.toString());
        processBuilder.directory(root.toFile());
        Map<String, String> env = processBuilder.environment();
        env.put("ACCOUNTING_PORTAL_SCOPE_GUARDRAIL_DOC", fixturePaths.guardrailDoc().toString());
        env.put("ACCOUNTING_PORTAL_ENDPOINT_MAP_DOC", fixturePaths.endpointMapDoc().toString());
        env.put("ACCOUNTING_PORTAL_HANDOFF_DOC", fixturePaths.handoffDoc().toString());
        env.put("ACCOUNTING_PORTAL_ENDPOINT_INVENTORY_DOC", fixturePaths.endpointInventoryDoc().toString());

        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(exitCode, stdout, stderr);
    }

    private void replaceInFile(Path path, String target, String replacement) throws IOException {
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String updated = original.replace(target, replacement);
        if (original.equals(updated)) {
            throw new IllegalStateException("Failed to replace fixture token in " + path + ": " + target);
        }
        Files.writeString(path, updated, StandardCharsets.UTF_8);
    }

    private FixturePaths writeFixture(int reportsCount) throws IOException {
        Path guardrailDoc = tempDir.resolve("ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md");
        Path endpointMapDoc = tempDir.resolve("accounting-portal-endpoint-map.md");
        Path handoffDoc = tempDir.resolve("accounting-portal-frontend-engineer-handoff.md");
        Path endpointInventoryDoc = tempDir.resolve("endpoint-inventory.md");

        Files.writeString(guardrailDoc, """
                # Accounting Portal Frontend Scope Guardrail
                HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.
                ## Change-Control Rule
                Updated portal endpoint map and frontend handoff docs for every affected portal.
                Updated `docs/endpoint-inventory.md` module mapping and examples.
                """);

        Files.writeString(endpointMapDoc, """
                # Accounting Portal Endpoint Map
                HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.
                docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md
                ## Purchasing & Payables
                ### purchasing-workflow-controller
                ## Inventory & Costing
                ### raw-material-controller
                ### inventory-adjustment-controller
                ## HR & Payroll
                ### hr-controller
                ### hr-payroll-controller
                ## Reports & Reconciliation
                ### report-controller
                | `GET /api/v1/purchasing/purchase-orders` |
                | `GET /api/v1/finished-goods/stock-summary` |
                | `GET /api/v1/reports/inventory-valuation` |
                | `GET /api/v1/hr/employees` |
                """);

        Files.writeString(handoffDoc, """
                # Accounting Portal Frontend Handoff
                HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.
                ## Purchasing & Payables
                ## Inventory & Costing
                ## HR & Payroll
                ## Reports & Reconciliation
                | `poListPurchaseOrders` | GET | `/api/v1/purchasing/purchase-orders` |
                | `finishedGoodGetStockSummary` | GET | `/api/v1/finished-goods/stock-summary` |
                | `reportInventoryValuation` | GET | `/api/v1/reports/inventory-valuation` |
                | `hrEmployees` | GET | `/api/v1/hr/employees` |
                """);

        Files.writeString(endpointInventoryDoc, """
                # Endpoint Inventory
                HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.
                docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md
                | Module | Path count | Examples |
                |---|---:|---|
                | `hr` | 11 | /api/v1/hr/employees |
                | `purchasing` | 7 | /api/v1/purchasing/purchase-orders |
                | `inventory` | 5 | /api/v1/finished-goods/stock-summary |
                | `reports` | %d | /api/v1/reports/inventory-valuation |
                - `GET` `/api/v1/purchasing/purchase-orders`
                - `GET` `/api/v1/finished-goods/stock-summary`
                - `GET` `/api/v1/reports/inventory-valuation`
                - `GET` `/api/v1/hr/employees`
                """.formatted(reportsCount));

        return new FixturePaths(guardrailDoc, endpointMapDoc, handoffDoc, endpointInventoryDoc);
    }

    private Path repoRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("scripts/guard_accounting_portal_scope_contract.sh"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Could not locate repository root");
    }

    private record FixturePaths(Path guardrailDoc,
                                Path endpointMapDoc,
                                Path handoffDoc,
                                Path endpointInventoryDoc) {
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
