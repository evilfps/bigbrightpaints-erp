@echo off
echo Running Maven tests...
echo.

REM Set Maven path for this session
set MAVEN_HOME=C:\Users\ASUS\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11
set PATH=%PATH%;%MAVEN_HOME%\bin

REM Change to erp-domain directory
cd /d "%~dp0erp-domain"

REM Run tests
echo Executing: mvn test
call C:\Users\ASUS\Downloads\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin\mvn.cmd test

echo.
echo Tests completed!
pause