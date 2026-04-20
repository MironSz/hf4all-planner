@echo off
REM Run the pathfinder benchmark and append one row to benchmark-results.csv.
REM
REM Usage:
REM   bench.cmd
REM   bench.cmd baseline
REM   bench.cmd "after fraction work"

setlocal
cd /d "%~dp0"

set "DESC=%*"

call mvnw.cmd -q -DskipTests package || exit /b 1
call mvnw.cmd -q exec:java ^
    -Dexec.mainClass=com.hf4all.planner.bench.BenchmarkRun ^
    -Dexec.cleanupDaemonThreads=false ^
    -Dexec.args="%DESC%"
