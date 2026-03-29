#!/bin/bash
# Codex / Claude web cloud runner setup script.
# Add this as the "Setup script" in your Codex cloud environment settings.
# This runs during the setup phase which has internet access, pre-caching
# all Maven dependencies so the agent phase doesn't need network.
set -euo pipefail

echo "=== Cloud Runner Maven Setup ==="

# Install Maven if not present
if ! command -v mvn &>/dev/null; then
    echo "Installing Maven..."
    if command -v sdk &>/dev/null; then
        sdk install maven 3.9.9
    elif command -v apt-get &>/dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y -qq maven
    elif command -v yum &>/dev/null; then
        sudo yum install -y maven
    else
        echo "WARNING: Cannot install Maven automatically. Ensure Maven is available."
    fi
fi

# Pre-download dependencies from erp-domain (the only Maven project)
if [ -d "erp-domain/pom.xml" ] || [ -f "erp-domain/pom.xml" ]; then
    echo "Pre-caching Maven dependencies..."
    cd erp-domain
    MIGRATION_SET=v2 mvn -q -ntp -DskipTests dependency:go-offline 2>/dev/null || \
        MIGRATION_SET=v2 mvn -q -ntp -DskipTests compile 2>/dev/null || \
        echo "WARNING: Maven dependency download had issues. Tests may need network."
    cd ..
else
    echo "WARNING: erp-domain/pom.xml not found. Skipping Maven setup."
fi

echo "=== Cloud Runner Setup Complete ==="
