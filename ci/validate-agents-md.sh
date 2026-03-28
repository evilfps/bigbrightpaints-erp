#!/usr/bin/env bash
# Validates AGENTS.md commands and references remain accurate
# Usage: bash ci/validate-agents-md.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENTS_MD="$REPO_ROOT/AGENTS.md"

echo "=== Validating AGENTS.md ==="

if [[ ! -f "$AGENTS_MD" ]]; then
    echo "ERROR: AGENTS.md not found at $AGENTS_MD"
    exit 1
fi

errors=0

# Check 1: Verify referenced files exist
echo "Checking referenced files..."
while IFS= read -r line; do
    # Extract file references like `path/to/file`
    if [[ "$line" =~ \`([^[:space:]\`]+\.(md|sh|yml|yaml|json|java))\` ]]; then
        file_ref="${BASH_REMATCH[1]}"
        # Skip if it's a URL or command
        if [[ ! "$file_ref" =~ ^(http|https|ftp) ]] && [[ ! "$file_ref" =~ ^\$ ]]; then
            full_path="$REPO_ROOT/$file_ref"
            if [[ ! -f "$full_path" ]] && [[ ! -d "$full_path" ]]; then
                echo "ERROR: Referenced file not found: $file_ref"
                ((errors++))
            fi
        fi
    fi
done < "$AGENTS_MD"

# Check 2: Verify mvn commands are valid
echo "Checking Maven commands..."
while IFS= read -r line; do
    if [[ "$line" =~ mvn[[:space:]]+([a-z:[:space:]-]+) ]]; then
        mvn_cmd="${BASH_REMATCH[1]}"
        # Extract the phase/goal
        phase=$(echo "$mvn_cmd" | awk '{print $1}')
        # Valid Maven phases/goals for this project
        valid_phases=("compile" "test" "verify" "package" "clean" "install" "spotless:check" "spotless:apply" "checkstyle:check")
        if [[ ! " ${valid_phases[*]} " =~ " ${phase} " ]]; then
            echo "WARNING: Unusual Maven command: mvn $mvn_cmd"
        fi
    fi
done < "$AGENTS_MD"

# Check 3: Verify bash scripts exist and are executable
echo "Checking bash script references..."
while IFS= read -r line; do
    if [[ "$line" =~ bash[[:space:]]+([^[:space:]\`]+\.(sh)) ]]; then
        script_ref="${BASH_REMATCH[1]}"
        full_path="$REPO_ROOT/$script_ref"
        if [[ ! -f "$full_path" ]]; then
            echo "ERROR: Referenced script not found: $script_ref"
            ((errors++))
        elif [[ ! -x "$full_path" ]]; then
            echo "WARNING: Script not executable: $script_ref"
        fi
    fi
done < "$AGENTS_MD"

# Check 4: Verify critical commands work (quick validation)
echo "Validating critical commands from AGENTS.md..."

# Test Spotless check against a representative included Java source so AGENTS
# validation exercises the configured formatter path without failing on
# unrelated repo-wide formatting debt.
SPOTLESS_SAMPLE_FILE="src/main/java/com/bigbrightpaints/erp/ErpDomainApplication.java"
echo "Testing: cd erp-domain && MIGRATION_SET=v2 mvn spotless:check -q -DspotlessFiles=$SPOTLESS_SAMPLE_FILE"
if ! (cd "$REPO_ROOT/erp-domain" && MIGRATION_SET=v2 mvn spotless:check -q -DspotlessFiles="$SPOTLESS_SAMPLE_FILE" 2>&1); then
    echo "ERROR: Spotless check command failed"
    ((errors++))
fi

# Test compile command (syntax check)
echo "Testing: cd erp-domain && MIGRATION_SET=v2 mvn compile -q -DskipTests"
if ! (cd "$REPO_ROOT/erp-domain" && MIGRATION_SET=v2 mvn compile -q -DskipTests 2>&1); then
    echo "ERROR: Maven compile command failed"
    ((errors++))
fi

# Check 5: Verify AGENTS.md has required sections
echo "Checking required sections..."
required_sections=("Code Style" "Review Guidelines" "Pre-commit" "Governance")
for section in "${required_sections[@]}"; do
    if ! grep -qi "$section" "$AGENTS_MD"; then
        echo "ERROR: Missing required section: $section"
        ((errors++))
    fi
done

# Check 6: Verify last reviewed date is within 180 days
echo "Checking documentation freshness..."
if grep -q "Last reviewed:" "$AGENTS_MD"; then
    reviewed_date=$(grep "Last reviewed:" "$AGENTS_MD" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}')
    if [[ -n "$reviewed_date" ]]; then
        reviewed_epoch=$(date -j -f "%Y-%m-%d" "$reviewed_date" "+%s" 2>/dev/null || date -d "$reviewed_date" "+%s" 2>/dev/null || echo "0")
        current_epoch=$(date "+%s")
        days_old=$(( (current_epoch - reviewed_epoch) / 86400 ))
        if [[ $days_old -gt 180 ]]; then
            echo "WARNING: AGENTS.md last reviewed $days_old days ago (recommended: < 180 days)"
        else
            echo "✓ AGENTS.md reviewed $days_old days ago"
        fi
    fi
fi

# Summary
echo ""
echo "=== Validation Summary ==="
if [[ $errors -eq 0 ]]; then
    echo "✓ AGENTS.md validation passed"
    exit 0
else
    echo "✗ AGENTS.md validation failed with $errors error(s)"
    exit 1
fi
