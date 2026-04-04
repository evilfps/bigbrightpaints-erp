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
PR_CI_PARITY_PATH = REPO_ROOT / "scripts" / "pr_ci_parity.py"
MERGE_JACOCO_PATH = REPO_ROOT / "scripts" / "merge_jacoco_xml.py"


def load_module(module_name: str, file_path: Path):
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


ci_risk_router = load_module("ci_risk_router", RISK_ROUTER_PATH)
changed_files_coverage = load_module("changed_files_coverage", CHANGED_COVERAGE_PATH)
pr_ci_parity = load_module("pr_ci_parity", PR_CI_PARITY_PATH)
merge_jacoco_xml = load_module("merge_jacoco_xml", MERGE_JACOCO_PATH)


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

    def test_company_admin_and_core_security_sources_route_auth_tenant(self):
        for path in [
            "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java",
            "erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsController.java",
            "erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java",
        ]:
            with self.subTest(path=path):
                flags = ci_risk_router.compute_flags([path])

                self.assertEqual("true", flags["run_auth_tenant"])
                self.assertEqual("true", flags["run_persistence_smoke"])
                self.assertEqual("true", flags["run_codered_access"])
                self.assertEqual("true", flags["run_changed_coverage"])

    def test_openapi_contract_proof_changes_route_auth_tenant_without_widening_other_lanes(self):
        for path in [
            "openapi.json",
            "erp-domain/src/test/java/com/bigbrightpaints/erp/OpenApiSnapshotIT.java",
        ]:
            with self.subTest(path=path):
                flags = ci_risk_router.compute_flags([path])

                self.assertEqual("true", flags["run_auth_tenant"])
                self.assertEqual("false", flags["run_accounting"])
                self.assertEqual("false", flags["run_idempotency_outbox"])
                self.assertEqual("false", flags["run_business_slice"])
                self.assertEqual("false", flags["run_persistence_smoke"])
                self.assertEqual("false", flags["run_codered_access"])
                self.assertEqual("false", flags["run_codered_finance"])
                self.assertEqual("false", flags["run_changed_coverage"])

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

    def test_local_pr_parity_runner_counts_as_ci_infra(self):
        flags = ci_risk_router.compute_flags(["scripts/pr_ci_parity.py"])

        self.assertEqual("true", flags["run_auth_tenant"])
        self.assertEqual("true", flags["run_accounting"])
        self.assertEqual("true", flags["run_idempotency_outbox"])
        self.assertEqual("true", flags["run_business_slice"])
        self.assertEqual("true", flags["run_persistence_smoke"])
        self.assertEqual("false", flags["run_codered_access"])
        self.assertEqual("false", flags["run_codered_finance"])
        self.assertEqual("false", flags["run_changed_coverage"])

    def test_services_manifest_change_counts_as_ci_infra(self):
        flags = ci_risk_router.compute_flags([".factory/services.yaml"])

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
    def test_branch_merge_caps_duplicate_shard_coverage(self):
        merged = changed_files_coverage.merge_line_stats((0, 1, 1, 1), (0, 1, 1, 1))
        self.assertEqual((0, 1, 0, 2), merged)

    def test_branch_merge_unions_split_shard_coverage(self):
        merged = changed_files_coverage.merge_line_stats((0, 1, 2, 0), (0, 1, 0, 2))
        self.assertEqual((0, 1, 0, 2), merged)

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
        self.assertTrue(
            changed_files_coverage.is_structural_source_line(
                "salesOrderInvoiceCount);",
                False,
                "        return LegacyDispatchInvoiceLinkMatcher.isSlipLinkedToInvoice(",
            )
        )
        self.assertTrue(changed_files_coverage.is_structural_source_line("RawMaterialPurchase::getCompany,", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("and upper(trim(o.status)) in :statuses", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line('"Cannot auto-create packing slip"', False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("try {", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("lines", False))
        self.assertFalse(changed_files_coverage.is_structural_source_line("if (invoice == null) {", False))
        self.assertTrue(changed_files_coverage.is_structural_source_line("void save(Entity entity);", True))
        self.assertFalse(changed_files_coverage.is_structural_source_line("service.save(entity);", True))


class MergeJacocoXmlTest(unittest.TestCase):
    def test_merge_line_unions_split_shard_coverage(self):
        merged = merge_jacoco_xml.merge_line((0, 1, 2, 0), (0, 1, 0, 2))
        self.assertEqual((0, 1, 0, 2), merged)

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
    def test_pr_ci_parity_skip_summary_matches_ci_contract(self):
        summary = pr_ci_parity.create_changed_coverage_skip_summary(
            diff_base="abc123",
            changed_files_count=4,
            changed_runtime_source_count=0,
        )

        self.assertEqual(
            {
                "diff_base": "abc123",
                "skipped": True,
                "reason": "no_runtime_source_changes",
                "changed_files_count": 4,
                "changed_runtime_source_count": 0,
                "passes": True,
            },
            summary,
        )

    def test_pr_ci_parity_merge_gate_blocks_failed_jobs(self):
        blocking = pr_ci_parity.evaluate_merge_gate(
            {
                "knowledgebase-lint": "success",
                "architecture-check": "success",
                "enterprise-policy-check": "failure",
                "orchestrator-layer-check": "success",
                "pr-risk-router": "success",
                "pr-build": "success",
                "pr-auth-tenant": "skipped",
                "pr-accounting": "success",
                "pr-idempotency-outbox": "skipped",
                "pr-business-slice": "success",
                "pr-persistence-smoke": "success",
                "pr-codered-access": "skipped",
                "pr-codered-finance": "success",
                "pr-changed-coverage": "failure",
            }
        )

        self.assertEqual(
            {
                "enterprise-policy-check": "failure",
                "pr-changed-coverage": "failure",
            },
            blocking,
        )

    def test_services_manifest_exposes_pr_ci_parity_command(self):
        services_text = (REPO_ROOT / ".factory" / "services.yaml").read_text(encoding="utf-8")

        self.assertIn("pr-ci-parity:", services_text)
        self.assertIn('scripts/pr_ci_parity.py', services_text)
        self.assertIn('PR_CI_BASE', services_text)

    def test_runtime_probe_fails_closed_on_health_status(self):
        services_text = (REPO_ROOT / ".factory" / "services.yaml").read_text(encoding="utf-8")

        self.assertIn(
            "status=$(curl -s -o /tmp/factory-backend-auth.out -w '%{http_code}' http://localhost:8081/api/v1/auth/me || true)",
            services_text,
        )
        self.assertIn('[ "$status" = "200" ]', services_text)
        self.assertIn('[ "$status" = "401" ]', services_text)
        self.assertIn('[ "$status" = "403" ]', services_text)
        self.assertNotIn('echo "$status"', services_text)

    def test_services_manifest_exposes_release_proof_and_dispatch_handoff_guard(self):
        services_text = (REPO_ROOT / ".factory" / "services.yaml").read_text(encoding="utf-8")

        self.assertIn("release-proof:", services_text)
        self.assertIn("bash scripts/release_proof.sh", services_text)
        self.assertIn("guard_dispatch_frontend_handoff_contract.sh", services_text)

    def test_release_workflows_keep_deploy_proof_and_release_notes_separate(self):
        ci_workflow = (REPO_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        release_workflow = (REPO_ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")

        self.assertIn("bash scripts/release_proof.sh", ci_workflow)
        self.assertIn("does **not** prove deployability", release_workflow)
        self.assertIn("gate-release", release_workflow)

    def test_release_proof_script_combines_strict_smoke_targeted_proofs_and_contract_guards(self):
        release_proof = (REPO_ROOT / "scripts" / "release_proof.sh").read_text(encoding="utf-8")

        self.assertIn('echo "[release-proof] strict compose smoke"', release_proof)
        self.assertIn('DB_PORT="5433"', release_proof)
        self.assertIn("strict_compose up -d db rabbitmq mailhog", release_proof)
        self.assertIn("strict_compose up -d --build app", release_proof)
        self.assertIn("http://localhost:9090/actuator/health", release_proof)
        self.assertIn("http://localhost:9090/actuator/health/readiness", release_proof)
        self.assertIn("http://localhost:8081/api/v1/auth/me", release_proof)
        self.assertIn('bash "$ROOT_DIR/scripts/gate_release.sh"', release_proof)
        self.assertIn("CR_ProductionMonitoringContractTest", release_proof)
        self.assertIn("DispatchControllerTest", release_proof)
        self.assertIn("JournalEntryE2ETest", release_proof)
        self.assertIn("CompanyContextFilterControlPlaneBindingTest", release_proof)
        self.assertIn("guard_workflow_canonical_paths.sh", release_proof)
        self.assertIn("guard_dispatch_frontend_handoff_contract.sh", release_proof)

    def test_docs_only_lane_includes_factory_library_guidance_packets(self):
        review_policy = (REPO_ROOT / "scripts" / "enforce_codex_review_policy.sh").read_text(encoding="utf-8")
        agents = (REPO_ROOT / "AGENTS.md").read_text(encoding="utf-8")
        workflow = (REPO_ROOT / "docs" / "agents" / "WORKFLOW.md").read_text(encoding="utf-8")
        conventions = (REPO_ROOT / "docs" / "CONVENTIONS.md").read_text(encoding="utf-8")

        self.assertIn(".factory/library/*", review_policy)
        self.assertIn(".factory/library/**", agents)
        self.assertIn(".factory/library/**", workflow)
        self.assertIn(".factory/library/**", conventions)

    def test_dispatch_frontend_handoff_guard_script_passes(self):
        result = subprocess.run(
            ["bash", str(REPO_ROOT / "scripts" / "guard_dispatch_frontend_handoff_contract.sh")],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("[guard_dispatch_frontend_handoff_contract] OK", result.stdout)

    def test_gate_fast_prefers_mainline_diff_base_before_canonical_anchor(self):
        gate_fast = (REPO_ROOT / "scripts" / "gate_fast.sh").read_text(encoding="utf-8")

        origin_main_index = gate_fast.index('if git rev-parse --verify --quiet origin/main >/dev/null; then')
        canonical_index = gate_fast.index('if [[ -n "${CANONICAL_BASE_SHA:-}" ]] && git merge-base --is-ancestor "$CANONICAL_BASE_SHA" HEAD; then')
        self.assertLess(origin_main_index, canonical_index)

    def test_gate_release_and_core_allow_mainline_canonical_base_fallback(self):
        gate_release = (REPO_ROOT / "scripts" / "gate_release.sh").read_text(encoding="utf-8")
        gate_core = (REPO_ROOT / "scripts" / "gate_core.sh").read_text(encoding="utf-8")
        schema_drift_scan = (REPO_ROOT / "scripts" / "schema_drift_scan.sh").read_text(encoding="utf-8")
        time_api_scan = (REPO_ROOT / "scripts" / "time_api_scan.sh").read_text(encoding="utf-8")

        self.assertIn("for fallback_ref in main origin/main; do", gate_release)
        self.assertIn("for fallback_ref in main origin/main; do", gate_core)
        self.assertIn("VERIFY_LOCAL_SKIP_MVN_VERIFY=true", gate_release)
        self.assertIn("--diff-base <ref>", schema_drift_scan)
        self.assertIn("no changed migration files; skipping branch-scoped drift scan", schema_drift_scan)
        self.assertIn("no changed Java sources; skipping branch-scoped scan", time_api_scan)

    def test_generated_gate_artifact_directories_are_gitignored(self):
        gitignore = (REPO_ROOT / ".gitignore").read_text(encoding="utf-8")

        self.assertIn("artifacts/gate-core/", gitignore)
        self.assertIn("artifacts/gate-release/", gitignore)
        self.assertIn("artifacts/gate-reconciliation/", gitignore)


class PrAuthTenantManifestContractTest(unittest.TestCase):
    def test_manifest_includes_lane01_runtime_regression_bundle(self):
        manifest_lines = {
            line.strip()
            for line in (REPO_ROOT / "ci" / "pr_manifests" / "pr_auth_tenant.txt").read_text(encoding="utf-8").splitlines()
            if line.strip()
        }

        self.assertTrue(
            {
                "com.bigbrightpaints.erp.modules.company.CompanyControllerIT",
                "com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementServiceTest",
                "com.bigbrightpaints.erp.modules.auth.TenantRuntimeEnforcementAuthIT",
                "com.bigbrightpaints.erp.modules.auth.CompanyContextFilterControlPlaneBindingTest",
                "com.bigbrightpaints.erp.OpenApiSnapshotIT",
                "com.bigbrightpaints.erp.modules.admin.controller.AdminSettingsControllerTenantRuntimeContractTest",
                "com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyServiceTest",
                "com.bigbrightpaints.erp.modules.portal.service.TenantRuntimeEnforcementInterceptorTest",
                "com.bigbrightpaints.erp.modules.portal.PortalInsightsControllerIT",
                "com.bigbrightpaints.erp.modules.reports.ReportControllerSecurityIT",
                "com.bigbrightpaints.erp.truthsuite.runtime.TS_RuntimeTenantPolicyControlExecutableCoverageTest",
                "com.bigbrightpaints.erp.truthsuite.runtime.TS_RuntimeTenantRuntimeEnforcementTest",
            }.issubset(manifest_lines)
        )


if __name__ == "__main__":
    unittest.main()
