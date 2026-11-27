#!/usr/bin/env bash
# Builds the server at the version openapi.yaml states, runs it on a fresh
# database over that server's fixtures, made when missing, and runs
# DownloadsEndToEndTest against it.
# RABBIT_EARS_E2E_PORT picks the port; the server goes when this does.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
server="$("$here/scripts/server-source.sh")"
port="${RABBIT_EARS_E2E_PORT:-19178}"
data="$(mktemp -d)"
pid=""
cleanup() {
	[ -z "$pid" ] || kill "$pid" 2>/dev/null || true
	[ -z "$pid" ] || wait "$pid" 2>/dev/null || true
	rm -rf "$data"
}
trap cleanup EXIT

"$server/scripts/fetch-fixtures.sh"
(cd "$server/server" && go build -o "$data/rabbit-ears" ./cmd/rabbit-ears)
RABBIT_EARS_PORT="$port" RABBIT_EARS_DB="$data/re.db" RABBIT_EARS_DATA="$data" \
	"$data/rabbit-ears" >"$data/server.log" 2>&1 &
pid=$!
for _ in $(seq 1 60); do
	curl -fsS "http://localhost:$port/api/v2/health" >/dev/null 2>&1 && break
	sleep 1
done
curl -fsS "http://localhost:$port/api/v2/health" >/dev/null || {
	cat "$data/server.log" >&2
	exit 1
}
cd "$here"
RABBIT_EARS_E2E="http://localhost:$port" RABBIT_EARS_FIXTURES="$server/fixtures" \
	./gradlew --quiet :app:testDebugUnitTest --rerun --tests '*DownloadsEndToEndTest' || {
	tail -50 "$data/server.log" >&2
	exit 1
}
