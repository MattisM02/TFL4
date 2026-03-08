@echo off
setlocal

rem ── bench.cmd ─────────────────────────────────────────────────────
rem  Convenience wrapper for BenchCli.
rem
rem  Usage:
rem    bench --scenario json --quick
rem    bench --scenario alloc --warmupRequests 50 --measureRequests 100
rem    bench --merge-excel
rem    bench                          (interactive scenario prompt)
rem ──────────────────────────────────────────────────────────────────

rem Use JAVA_HOME from environment if set, otherwise fall back to Temurin
if not defined JAVA_HOME (
    set "JAVA_HOME=C:\Users\mme\.jdks\temurin-25.0.1"
    echo [bench] JAVA_HOME not set, using default: %JAVA_HOME%
) else (
    echo [bench] Using JAVA_HOME: %JAVA_HOME%
)

rem Compile and launch BenchCli, forwarding all arguments
call "%~dp0mvnw.cmd" -q compile exec:java -Dexec.args="%*"
