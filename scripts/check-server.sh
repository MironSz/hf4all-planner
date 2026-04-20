#!/bin/bash
# Check whether the HF4A planner server is running on :8080.
# Exits 0 and prints "UP ..." when responsive, exits 1 otherwise.
#
# Usage:  ./scripts/check-server.sh

PORT=8080
CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "http://localhost:$PORT/" 2>/dev/null || echo "000")

if [ "$CODE" = "200" ]; then
    echo "UP   — http://localhost:$PORT/ responding 200"
    exit 0
else
    echo "DOWN — no 200 on :$PORT (got: $CODE)"
    exit 1
fi
