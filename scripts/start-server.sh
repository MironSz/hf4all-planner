#!/bin/bash
# Compile the project with Maven and start the server. If a server is already
# running on :8080, it's gracefully stopped via /stop-hf4-planner first.
#
# Usage:  ./scripts/start-server.sh
# Logs:   target/server.log  (server stdout+stderr after startup)
# PID:    target/server.pid  (background wrapper pid — curl /stop-hf4-planner
#                             is still the preferred shutdown mechanism)

set -e
cd "$(dirname "$0")/.."

PORT=8080
LOG="target/server.log"
PIDFILE="target/server.pid"

mvn() {
    java -Dmaven.multiModuleProjectDirectory="$(pwd)" \
         -classpath .mvn/wrapper/maven-wrapper.jar \
         org.apache.maven.wrapper.MavenWrapperMain "$@"
}

is_up() {
    curl -s -o /dev/null -m 2 "http://localhost:$PORT/" 2>/dev/null
}

echo "=== Stopping any running server on :$PORT ==="
if is_up; then
    curl -s -m 3 "http://localhost:$PORT/stop-hf4-planner" >/dev/null || true
    for _ in $(seq 1 10); do
        is_up || { echo "Stopped."; break; }
        sleep 1
    done
    if is_up; then
        echo "WARN: server still responding after /stop-hf4-planner — continuing anyway"
    fi
else
    echo "Not running."
fi

mkdir -p target

echo "=== Compiling ==="
mvn -q compile

echo "=== Starting server (log: $LOG) ==="
# Dev wrapper: enable /stop-hf4-planner and editor save endpoints. Production
# deploys leave the flag at its default (false in server.properties).
nohup bash -c "$(declare -f mvn); mvn exec:java@run -Dserver.debug.endpoints.allow=true" > "$LOG" 2>&1 &
echo $! > "$PIDFILE"

# Wait for readiness
for i in $(seq 1 30); do
    if is_up; then
        echo "Server UP on http://localhost:$PORT/  (wrapper pid $(cat $PIDFILE))"
        exit 0
    fi
    sleep 1
done

echo "FAIL: server did not come up within 30s — see $LOG"
exit 1
