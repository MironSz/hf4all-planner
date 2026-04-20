#!/usr/bin/env bash
# Run the pathfinder benchmark and append one row to benchmark-results.csv.
#
# Usage:
#   ./bench.sh                         # no description
#   ./bench.sh baseline                # single-word description
#   ./bench.sh "after fraction work"   # multi-word (quote it)
#
# Runs directly on target/classes. If BenchmarkRun hasn't been compiled yet,
# this script compiles just that one file. To rebuild the whole project use
# the Maven wrapper separately.
#
# Windows-style backslashes in the classpath are intentional: Git Bash / MSYS
# mangles forward-slash, semicolon-separated classpaths before handing them
# to the native Windows JVM.
set -euo pipefail
cd "$(dirname "$0")"

CP='target\classes;C:\Users\Laptop\.m2\repository\com\google\code\gson\gson\2.11.0\gson-2.11.0.jar'

if [ ! -f "target/classes/com/hf4all/planner/bench/BenchmarkRun.class" ]; then
    javac -d target/classes -cp "$CP" src/main/java/com/hf4all/planner/bench/BenchmarkRun.java
fi

java -cp "$CP" com.hf4all.planner.bench.BenchmarkRun "$@"
