@echo off
setlocal
cd /d "%~dp0"
set "LENDLY_JAVA=java"
if defined JAVA_HOME (
  set "LENDLY_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  if exist "%ProgramFiles%\Java\latest\jdk-21\bin\java.exe" set "LENDLY_JAVA=%ProgramFiles%\Java\latest\jdk-21\bin\java.exe"
)
if not exist "target\lending-ledger-desktop.jar" (
  echo Open the ZIP with Extract All, or build the source project with mvn package.
  pause
  exit /b 1
)
"%LENDLY_JAVA%" -jar "target\lending-ledger-desktop.jar"
if errorlevel 1 (
  echo Check that Java 21 is installed and this folder is writable.
  pause
  exit /b 1
)
