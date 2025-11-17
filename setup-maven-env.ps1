# Setup Maven Environment Variables

# Set MAVEN_HOME
[Environment]::SetEnvironmentVariable('MAVEN_HOME', 'C:\Users\ASUS\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11', 'User')
Write-Host "MAVEN_HOME set successfully" -ForegroundColor Green

# Get current PATH
$currentPath = [Environment]::GetEnvironmentVariable('Path', 'User')

# Check if Maven is already in PATH
if ($currentPath -notlike '*apache-maven-3.9.11\bin*') {
    # Add Maven bin to PATH
    $newPath = $currentPath + ';C:\Users\ASUS\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin'
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
    Write-Host "Maven bin directory added to PATH successfully" -ForegroundColor Green
} else {
    Write-Host "Maven is already in your PATH" -ForegroundColor Yellow
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Maven environment setup complete!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "`nIMPORTANT: You need to:" -ForegroundColor Yellow
Write-Host "1. Close this terminal/command prompt" -ForegroundColor Yellow
Write-Host "2. Open a NEW terminal/command prompt" -ForegroundColor Yellow
Write-Host "3. Then you can use 'mvn' command from anywhere" -ForegroundColor Yellow
Write-Host "`nTo verify in the new window, type: mvn --version" -ForegroundColor Green