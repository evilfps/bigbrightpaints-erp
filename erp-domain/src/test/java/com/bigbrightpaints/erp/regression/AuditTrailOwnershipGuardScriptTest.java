package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditTrailOwnershipGuardScriptTest {

  @TempDir Path tempDir;

  @Test
  void guardFailsClosedWhenRequiredContractFileIsMissing() throws Exception {
    Path root = repoRoot();
    Path script = root.resolve("scripts/guard_audit_trail_ownership_contract.sh");
    Path missingDoc = tempDir.resolve("missing-audit-ownership.md");

    ProcessBuilder processBuilder = new ProcessBuilder("bash", script.toString());
    processBuilder.directory(root.toFile());
    Map<String, String> env = processBuilder.environment();
    env.put("AUDIT_TRAIL_OWNERSHIP_DOC", missingDoc.toString());

    Process process = processBuilder.start();
    int exitCode = process.waitFor();
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(exitCode).isNotEqualTo(0);
    assertThat(stderr).contains("missing required audit-ownership file");
  }

  private Path repoRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null) {
      if (java.nio.file.Files.exists(
          cursor.resolve("scripts/guard_audit_trail_ownership_contract.sh"))) {
        return cursor;
      }
      cursor = cursor.getParent();
    }
    throw new IllegalStateException("Unable to locate repository root");
  }
}
