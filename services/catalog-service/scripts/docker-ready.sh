#!/usr/bin/env bash
# DOCKER-LOCAL-1 preflight. Run this BEFORE the test suite or the local stack:
#
#   ./scripts/docker-ready.sh && ./mvnw test
#
# Rule: never debug an application or test failure until `docker info` is healthy. A
# Testcontainers hang is infrastructure failure, not a catalogue failure.
#
# If this script fails, the standing recovery sequence is: gracefully quit Docker Desktop; wait
# for its processes to exit (terminate only your own Docker Desktop backend processes if the
# graceful quit hangs); relaunch; wait until BOTH `docker info` and `docker ps` answer; re-run
# the affected suite FROM THE BEGINNING. No sudo repairs, no Factory Reset, no prune, no volume
# or image deletion, no Desktop setting changes without asking the CEO.
set -euo pipefail

TIMEOUT="${DOCKER_READY_TIMEOUT:-60}"

echo "Checking Docker Desktop (up to ${TIMEOUT}s)..."
for ((i = 1; i <= TIMEOUT; i++)); do
  if docker info >/dev/null 2>&1; then
    # `docker info` can answer while the container API is still wedged; require both.
    if docker ps >/dev/null 2>&1; then
      echo "Docker engine is ready (info + ps answered after ${i}s)."
      exit 0
    fi
  fi
  sleep 1
done

echo "Docker engine did not become ready within ${TIMEOUT} seconds."
echo "Do not debug test failures yet: restart Docker Desktop first (see the header of this script)."
exit 1
