@echo off
echo Setting up Maven environment variables...

REM Set MAVEN_HOME
setx MAVEN_HOME "C:\Users\ASUS\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11" /M 2>nul
if %errorlevel% neq 0 (
    echo Setting MAVEN_HOME for current user instead (admin rights not available)...
    setx MAVEN_HOME "C:\Users\ASUS\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11"
)

REM Add Maven bin to PATH
echo Adding Maven to PATH...
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v PATH 2^>nul') do set "CURRENT_USER_PATH=%%b"

REM Check if Maven is already in PATH
echo %CURRENT_USER_PATH% | findstr /C:"apache-maven-3.9.11\bin" >nul
if %errorlevel% equ 0 (
    echo Maven is already in your PATH
) else (
    setx PATH "%CURRENT_USER_PATH%;C:\Users\ASUS\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin"
    echo Maven has been added to your PATH
)

echo.
echo ========================================
echo Maven PATH setup complete!
echo ========================================
echo.
echo IMPORTANT: You need to:
echo 1. Close this command prompt window
echo 2. Open a NEW command prompt window
echo 3. Then you can use 'mvn' command from anywhere
echo.
echo To verify, in the new window type: mvn --version
echo.
pause