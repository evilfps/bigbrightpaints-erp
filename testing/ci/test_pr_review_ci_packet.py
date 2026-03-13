import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RISK_ROUTER_PATH = REPO_ROOT / "scripts" / "ci_risk_router.py"
CHANGED_COVERAGE_PATH = REPO_ROOT / "scripts" / "changed_files_coverage.py"


def load_module(module_name: str, file_path: Path):
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


ci_risk_router = load_module("ci_risk_router", RISK_ROUTER_PATH)
changed_files_coverage = load_module("changed_files_coverage", CHANGED_COVERAGE_PATH)


class CiRiskRouterTest(unittest.TestCase):
    def test_pr_fast_profile_remains_defined_for_pr_callers(self):
        pom_text = (REPO_ROOT / "erp-domain" / "pom.xml").read_text(encoding="utf-8")

        self.assertIn("<id>pr-fast</id>", pom_text)
        self.assertIn("<include>**/smoke/**/*Test.java</include>", pom_text)

    def test_docs_only_change_skips_runtime_shards_and_changed_coverage(self):
        flags = ci_risk_router.compute_flags(["docs/SECURITY.md"])

        self.assertEqual("false", flags["run_auth_tenant"])
        self.assertEqual("false", flags["run_accounting"])
        self.assertEqual("false", flags["run_idempotency_outbox"])
        self.assertEqual("false", flags["run_business_slice"])
        self.assertEqual("false", flags["run_persistence_smoke"])
        self.assertEqual("false", flags["run_codered_access"])
        self.assertEqual("false", flags["run_codered_finance"])
        self.assertEqual("false", flags["run_changed_coverage"])

    def test_auth_source_routes_auth_family_only(self):
        flags = ci_risk_router.compute_flags(
            [
                "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/AuthService.java",
            ]
        )

        self.assertEqual("true", flags["run_auth_tenant"])
        self.assertEqual("true", flags["run_persistence_smoke"])
        self.assertEqual("true", flags["run_codered_access"])
        self.assertEqual("true", flags["run_changed_coverage"])
        self.assertEqual("false", flags["run_accounting"])
        self.assertEqual("false", flags["run_idempotency_outbox"])
        self.assertEqual("false", flags["run_business_slice"])
        self.assertEqual("false", flags["run_codered_finance"])

    def test_ci_infra_change_runs_core_pr_shards_but_not_codered_or_changed_coverage(self):
        flags = ci_risk_router.compute_flags([".github/workflows/ci.yml"])

        self.assertEqual("true", flags["run_auth_tenant"])
        self.assertEqual("true", flags["run_accounting"])
        self.assertEqual("true", flags["run_idempotency_outbox"])
        self.assertEqual("true", flags["run_business_slice"])
        self.assertEqual("true", flags["run_persistence_smoke"])
        self.assertEqual("false", flags["run_codered_access"])
        self.assertEqual("false", flags["run_codered_finance"])
        self.assertEqual("false", flags["run_changed_coverage"])

    def test_workflow_surface_change_still_routes_codered_finance(self):
        flags = ci_risk_router.compute_flags(
            [
                "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesOrderService.java",
            ]
        )

        self.assertEqual("true", flags["run_accounting"])
        self.assertEqual("true", flags["run_business_slice"])
        self.assertEqual("true", flags["run_persistence_smoke"])
        self.assertEqual("true", flags["run_codered_finance"])


class ChangedFilesCoverageTest(unittest.TestCase):
    def test_branch_merge_is_conservative(self):
        merged = changed_files_coverage.merge_line_stats((0, 1, 1, 1), (0, 1, 1, 1))
        self.assertEqual((0, 1, 1, 1), merged)

    def test_structural_classifier_accepts_java_declarations_and_continuations(self):
        self.assertTrue(changed_files_coverage.is_structural_source_line("public final class Demo {", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("private Demo() {", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line('private static final String MODE = "CREDIT";', False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("private Long invoiceId;", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line('static final String DEFAULT_MODE = "CREDIT";', False))
        self.assertTrue(changed_files_coverage.is_structural_source_line('String fileName = "delivery-challan-";', False))
        self.assertTrue(
            changed_files_coverage.is_structural_source_line(
                "private boolean isSlipLinkedToInvoice(PackagingSlip slip,",
                False,
            )
        )
        self.assertTrue(
            changed_files_coverage.is_structural_source_line(
                "private SalesProformaBoundaryService.CommercialAssessment syncFactoryDispatchReadiness(",
                False,
            )
        )
        self.assertTrue(changed_files_coverage.is_structural_source_line("Invoice invoice,", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("int salesOrderInvoiceCount) {", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("salesOrderInvoiceCount);", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("RawMaterialPurchase::getCompany,", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("and upper(trim(o.status)) in :statuses", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line('"Cannot auto-create packing slip"', False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("try {", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("lines", False))
        self.assertFalse(changed_files_coverage.is_structural_source_line("if (invoice == null) {", False))

    def test_package_declaration_fallback_maps_misplaced_source_file(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            repo_dir = Path(tmp_dir)
            java_file = repo_dir / "erp-domain/src/main/java/com/example/internal/Demo.java"
            java_file.parent.mkdir(parents=True, exist_ok=True)

            self.run_git(repo_dir, "init")
            self.run_git(repo_dir, "config", "user.name", "Factory Droid")
            self.run_git(repo_dir, "config", "user.email", "factory@example.com")

            java_file.write_text(
                "package com.example.service;\npublic class Demo {\n  int value() { return 1; }\n}\n",
                encoding="utf-8",
            )
            self.run_git(repo_dir, "add", ".")
            self.run_git(repo_dir, "commit", "-m", "base")
            base_sha = self.run_git(repo_dir, "rev-parse", "HEAD")

            java_file.write_text(
                "package com.example.service;\npublic class Demo {\n  int value() { return 2; }\n}\n",
                encoding="utf-8",
            )
            self.run_git(repo_dir, "add", ".")
            self.run_git(repo_dir, "commit", "-m", "change")

            jacoco_file = repo_dir / "jacoco.xml"
            jacoco_file.write_text(
                """
<report name=\"test\">
  <package name=\"com/example/service\">
    <sourcefile name=\"Demo.java\">
      <line nr=\"3\" mi=\"0\" ci=\"1\" mb=\"0\" cb=\"0\"/>
    </sourcefile>
  </package>
</report>
""".strip()
                + "\n",
                encoding="utf-8",
            )
            summary_file = repo_dir / "summary.json"

            result = subprocess.run(
                [
                    sys.executable,
                    str(CHANGED_COVERAGE_PATH),
                    "--jacoco",
                    str(jacoco_file),
                    "--diff-base",
                    base_sha,
                    "--src-root",
                    "erp-domain/src/main/java",
                    "--fail-on-vacuous",
                    "--output",
                    str(summary_file),
                ],
                cwd=repo_dir,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            summary = json.loads(summary_file.read_text(encoding="utf-8"))
            self.assertFalse(summary["missing_coverage"])
            self.assertEqual([], summary["coverage_skipped_files"])
            self.assertTrue(summary["passes"])

    def test_changed_source_without_jacoco_mapping_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            repo_dir = Path(tmp_dir)
            java_file = repo_dir / "erp-domain/src/main/java/com/example/Demo.java"
            java_file.parent.mkdir(parents=True, exist_ok=True)

            self.run_git(repo_dir, "init")
            self.run_git(repo_dir, "config", "user.name", "Factory Droid")
            self.run_git(repo_dir, "config", "user.email", "factory@example.com")

            java_file.write_text(
                "package com.example;\npublic class Demo {\n  int value() { return 1; }\n}\n",
                encoding="utf-8",
            )
            self.run_git(repo_dir, "add", ".")
            self.run_git(repo_dir, "commit", "-m", "base")
            base_sha = self.run_git(repo_dir, "rev-parse", "HEAD")

            java_file.write_text(
                "package com.example;\npublic class Demo {\n  int value() { return 2; }\n}\n",
                encoding="utf-8",
            )
            self.run_git(repo_dir, "add", ".")
            self.run_git(repo_dir, "commit", "-m", "change")

            jacoco_file = repo_dir / "jacoco.xml"
            jacoco_file.write_text("<report name=\"test\"></report>\n", encoding="utf-8")
            summary_file = repo_dir / "summary.json"

            result = subprocess.run(
                [
                    sys.executable,
                    str(CHANGED_COVERAGE_PATH),
                    "--jacoco",
                    str(jacoco_file),
                    "--diff-base",
                    base_sha,
                    "--src-root",
                    "erp-domain/src/main/java",
                    "--fail-on-vacuous",
                    "--output",
                    str(summary_file),
                ],
                cwd=repo_dir,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            summary = json.loads(summary_file.read_text(encoding="utf-8"))
            self.assertTrue(summary["missing_coverage"])
            self.assertEqual(
                ["erp-domain/src/main/java/com/example/Demo.java"],
                summary["coverage_skipped_files"],
            )

    @staticmethod
    def run_git(repo_dir: Path, *args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=repo_dir, text=True).strip()


class RuntimeProbeContractTest(unittest.TestCase):
    def test_runtime_probe_fails_closed_on_health_status(self):
        services_text = (REPO_ROOT / ".factory" / "services.yaml").read_text(encoding="utf-8")

        self.assertIn(
            "status=$(curl -s -o /tmp/factory-backend-auth.out -w '%{http_code}' http://localhost:8081/api/v1/auth/me || true)",
            services_text,
        )
        self.assertIn('[ "$status" = "200" ]', services_text)
        self.assertIn('[ "$status" = "401" ]', services_text)
        self.assertIn('[ "$status" = "403" ]', services_text)


if __name__ == "__main__":
    unittest.main()
