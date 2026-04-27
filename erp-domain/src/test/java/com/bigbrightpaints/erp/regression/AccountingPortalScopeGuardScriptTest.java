package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountingPortalScopeGuardScriptTest {

  @TempDir Path tempDir;

  @Test
  void guardPassesWhenCanonicalDocsStayAligned() throws Exception {
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
  void guardFailsWhenRequiredPortalDocIsMissing() throws Exception {
    FixturePaths fixturePaths = writeFixture(13);
    Files.delete(fixturePaths.portalDoc());

    ProcessResult result = runGuard(fixturePaths);

    assertThat(result.exitCode()).isNotEqualTo(0);
    assertThat(result.stderr()).contains("missing required scope contract file");
  }

  @Test
  void guardFailsWhenEndpointInventoryMethodTokenIsMalformed() throws Exception {
    FixturePaths fixturePaths = writeFixture(13);
    replaceInFile(
        fixturePaths.endpointInventoryDoc(),
        "- `GET, POST` `/api/v1/hr/employees`\n",
        "- `,` `/api/v1/hr/employees`\n");

    ProcessResult result = runGuard(fixturePaths);

    assertThat(result.exitCode()).isNotEqualTo(0);
    assertThat(result.stderr()).contains("required endpoint evidence missing");
    assertThat(result.stderr()).contains("/api/v1/hr/employees");
  }

  @Test
  void guardFailsWhenPortalDocOmitsMakerCheckerWorkflow() throws Exception {
    FixturePaths fixturePaths = writeFixture(13);
    replaceInFile(
        fixturePaths.portalDoc(),
        "drive request-close, approve-close, and reject-close workflow\n",
        "");

    ProcessResult result = runGuard(fixturePaths);

    assertThat(result.exitCode()).isNotEqualTo(0);
    assertThat(result.stderr()).contains("maker-checker period-close ownership");
  }

  @Test
  void guardFailsWhenFrontendApiOmitsAccountingPortalPlacement() throws Exception {
    FixturePaths fixturePaths = writeFixture(13);
    replaceInFile(
        fixturePaths.frontendApiDoc(),
        "- **accounting:** COA, journals, reconciliation, period close, reports\n",
        "");

    ProcessResult result = runGuard(fixturePaths);

    assertThat(result.exitCode()).isNotEqualTo(0);
    assertThat(result.stderr()).contains("accounting portal placement explicit");
  }

  @Test
  void guardPassesWhenOnlyPerlRegexFallbackIsAvailable() throws Exception {
    FixturePaths fixturePaths = writeFixture(13);
    Path toolDir = tempDir.resolve("perl-fallback-bin");
    Files.createDirectories(toolDir);
    symlinkTool(toolDir, "dirname");
    symlinkTool(toolDir, "grep");
    symlinkTool(toolDir, "perl");

    ProcessResult result = runGuard(fixturePaths, toolDir.toString());

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("[guard_accounting_portal_scope_contract] OK");
  }

  private ProcessResult runGuard(FixturePaths fixturePaths) throws Exception {
    return runGuard(fixturePaths, null);
  }

  private ProcessResult runGuard(FixturePaths fixturePaths, String pathOverride) throws Exception {
    Path root = repoRoot();
    Path script = root.resolve("scripts/guard_accounting_portal_scope_contract.sh");

    ProcessBuilder processBuilder = new ProcessBuilder("bash", script.toString());
    processBuilder.directory(root.toFile());
    Map<String, String> env = processBuilder.environment();
    env.put("ACCOUNTING_PORTAL_SCOPE_GUARDRAIL_DOC", fixturePaths.guardrailDoc().toString());
    env.put("ACCOUNTING_PORTAL_PORTAL_DOC", fixturePaths.portalDoc().toString());
    env.put("ACCOUNTING_PORTAL_FRONTEND_API_DOC", fixturePaths.frontendApiDoc().toString());
    env.put(
        "ACCOUNTING_PORTAL_ENDPOINT_INVENTORY_DOC", fixturePaths.endpointInventoryDoc().toString());
    if (pathOverride != null) {
      env.put("PATH", pathOverride);
    }

    Process process = processBuilder.start();
    int exitCode = process.waitFor();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(exitCode, stdout, stderr);
  }

  private void symlinkTool(Path toolDir, String name) throws IOException {
    Files.createSymbolicLink(toolDir.resolve(name), resolveTool(name));
  }

  private Path resolveTool(String name) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      throw new IllegalStateException("PATH is not set; could not resolve tool " + name);
    }
    for (String entry : path.split(java.io.File.pathSeparator)) {
      Path candidate = Paths.get(entry, name);
      if (Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not resolve required tool from PATH: " + name);
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
    Path portalDoc = tempDir.resolve("accounting-portal-README.md");
    Path frontendApiDoc = tempDir.resolve("frontend-api-README.md");
    Path endpointInventoryDoc = tempDir.resolve("endpoint-inventory.md");

    Files.writeString(
        guardrailDoc,
        """
        # Accounting Portal Scope Guardrail
        HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.
        ## Change-Control Rule
        Updated canonical portal and frontend API docs for every affected portal.
        Updated `docs/endpoint-inventory.md` module mapping and examples.
        """);

    Files.writeString(
        portalDoc,
        """
        # Accounting Portal
        - drive request-close, approve-close, and reject-close workflow
        - review and import opening stock only after accounting readiness is complete
        - Direct `POST /api/v1/accounting/periods/{periodId}/close` is not a frontend action.
        """);

    Files.writeString(
        frontendApiDoc,
        """
# Frontend API Contract
- **accounting:** COA, journals, reconciliation, period close, reports
- **Period close:** frontend must follow maker-checker flow: request close -> tenant-admin approvals inbox -> approve/reject close.
""");

    Files.writeString(
        endpointInventoryDoc,
        """
        # Endpoint Inventory
        HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.
        docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md
        | Module | Path count | Examples |
        |---|---:|---|
        | `hr` | 11 | /api/v1/hr/employees |
        | `purchasing` | 7 | /api/v1/purchasing/purchase-orders |
        | `inventory` | 5 | /api/v1/inventory/adjustments |
        | `finished-goods` | 5 | /api/v1/finished-goods/stock-summary |
        | `portal` | 3 | /api/v1/portal/finance/ledger |
        | `reports` | %d | /api/v1/reports/inventory-valuation |
        - `GET, POST` `/api/v1/hr/employees`
        - `GET, POST` `/api/v1/purchasing/purchase-orders`
        - `GET` `/api/v1/finished-goods/stock-summary`
        - `GET` `/api/v1/portal/finance/ledger`
        - `GET` `/api/v1/portal/finance/invoices`
        - `GET` `/api/v1/portal/finance/aging`
        - `GET` `/api/v1/reports/inventory-valuation`
        """
            .formatted(reportsCount));

    return new FixturePaths(guardrailDoc, portalDoc, frontendApiDoc, endpointInventoryDoc);
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

  private record FixturePaths(
      Path guardrailDoc, Path portalDoc, Path frontendApiDoc, Path endpointInventoryDoc) {}

  private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
