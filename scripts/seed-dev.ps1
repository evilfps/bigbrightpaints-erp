Param(
  [string]$DbContainer = "erp_db"
)

$ErrorActionPreference = "Stop"

$running = (& docker ps --format '{{.Names}}')
if (-not ($running -split "`n" | Where-Object { $_ -eq $DbContainer })) {
  Write-Error "Container $DbContainer not running. Start compose first (docker compose up -d)."
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$seedFile = Join-Path $scriptDir 'seed-dev.sql'

Write-Host "Copying seed SQL into $DbContainer..."
& docker cp $seedFile "$DbContainer`:/tmp/seed-dev.sql"

Write-Host "Applying seed data..."
& docker exec -i $DbContainer psql -U erp -d erp_domain -f /tmp/seed-dev.sql

Write-Host "Done. Seed data applied."

