# LEAD-014 Evidence Run (Actuator health endpoint contract)

## Objective
Determine whether app-port `/actuator/health` 404 is expected by configuration/docs or indicates an ops defect.

## Planned probes
1) GET health on management port.
2) GET health on app port.
3) Capture config/docs indicating management port/base path expectations.

## Command log
```bash
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082939Z_actuator_health.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082949Z_health_gets_app_port.txt
mkdir -p tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/{curl,OUTPUTS}
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/curl/01_health_management.sh
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/curl/02_health_app.sh
chmod +x tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/curl/*.sh
TS=$(date -u +"%Y%m%dT%H%M%SZ"); DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 docker compose up -d 2>&1 | tee "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_compose_up.txt"
rg -n "ERP_SECURITY_ENCRYPTION_KEY" .env.example .env.prod.template
TS=$(date -u +"%Y%m%dT%H%M%SZ"); ERP_SECURITY_ENCRYPTION_KEY=uFs4OAWuRLDPsS60S9JXVBCWrz0VY49exrq_MT6hX2U DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 docker compose up -d 2>&1 | tee "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_compose_up.txt"
rg -n "JWT_SECRET" .env.prod.template
TS=$(date -u +"%Y%m%dT%H%M%SZ"); ERP_SECURITY_ENCRYPTION_KEY=uFs4OAWuRLDPsS60S9JXVBCWrz0VY49exrq_MT6hX2U JWT_SECRET=b2YKFKNDK6jiJw5Xyn9yX4nKQR3fpPWBbMUoTrjIg6SMSiLVFv_BBsTpPUKvmdUU DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 docker compose up -d 2>&1 | tee "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_compose_up.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); ERP_SECURITY_ENCRYPTION_KEY=uFs4OAWuRLDPsS60S9JXVBCWrz0VY49exrq_MT6hX2U JWT_SECRET=b2YKFKNDK6jiJw5Xyn9yX4nKQR3fpPWBbMUoTrjIg6SMSiLVFv_BBsTpPUKvmdUU DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 docker compose ps > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_compose_ps.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); MANAGEMENT_URL=http://localhost:19090 bash tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/curl/01_health_management.sh > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_health_management.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); APP_URL=http://localhost:8081 bash tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/curl/02_health_app.sh > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_health_app.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); rg -n "management" erp-domain/src/main/resources/application*.yml > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_management_config_rg.txt"
rg -n "^management" -n erp-domain/src/main/resources/application.yml
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '60,120p' erp-domain/src/main/resources/application.yml > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_application_yml_management.txt"
rg -n "management" erp-domain/src/main/resources/application-prod.yml
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '80,140p' erp-domain/src/main/resources/application-prod.yml > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_application_prod_yml_management.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); rg -n "actuator|health" docs erp-domain/docs tasks/erp_logic_audit -g "*.md" > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_docs_actuator_rg.txt"
tail -n 40 tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/*_docs_actuator_rg.txt
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/*_application_yml_management.txt
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/*_application_prod_yml_management.txt
rg -n "MANAGEMENT|management" docker-compose.yml
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '40,90p' docker-compose.yml > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_docker_compose_management.txt"
rg -n "SPRING_PROFILES_ACTIVE" docker-compose.yml
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '46,70p' docker-compose.yml > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/${TS}_docker_compose_profiles.txt"
ls -1 docs/ops_and_debug
rg -n "health|actuator|management" docs/ops_and_debug/EVIDENCE.md
ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS | head -n 40
git status -sb
sed -n '1,200p' tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/RUN.md
git add -p tasks/erp_logic_audit/HUNT_NOTEBOOK.md
git add -p tasks/erp_logic_audit/FINDINGS_INDEX.md
git add docs/ops_and_debug/EVIDENCE.md
git add tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014
git commit -m "audit-inv: LEAD-014 health endpoint contract evidence + disposition"
# planned post-commit metadata alignment
git rev-parse HEAD
git add tasks/erp_logic_audit/README.md HYDRATION.md
git commit -m "audit-inv: metadata alignment (README/HYDRATION tip SHA)"
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092046Z_compose_up.txt` (missing `ERP_SECURITY_ENCRYPTION_KEY`)
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092105Z_compose_up.txt` (missing `JWT_SECRET`)
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092114Z_compose_up.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092133Z_compose_ps.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092137Z_health_management.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092141Z_health_app.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092147Z_management_config_rg.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092156Z_application_yml_management.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092202Z_application_prod_yml_management.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092206Z_docs_actuator_rg.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092533Z_docker_compose_management.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092547Z_docker_compose_profiles.txt`
