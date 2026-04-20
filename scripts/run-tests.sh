#!/bin/bash
# Run all Maven tests and capture output.
#
# Usage:    ./scripts/run-tests.sh
# Console:  full mvn output streamed live
# Log:      target/test-output.log         (same content, for later grep)
# Reports:  target/surefire-reports/*.txt  (per-class Surefire detail)

set -o pipefail
cd "$(dirname "$0")/.."

LOG="target/test-output.log"
mkdir -p target

echo "Running tests → console + $LOG"
java -Dmaven.multiModuleProjectDirectory="$(pwd)" \
     -classpath .mvn/wrapper/maven-wrapper.jar \
     org.apache.maven.wrapper.MavenWrapperMain test 2>&1 | tee "$LOG"

exit ${PIPESTATUS[0]}
