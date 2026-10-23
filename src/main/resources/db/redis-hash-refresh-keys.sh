#!/usr/bin/env bash
# One-time migration, run once right after deploying the release that hashes refresh token keys
# (RefreshTokenService.keyOf): keys refresh:<token> and refresh:consumed:<token>, and the fields of
# refresh:idx:<userId>, become SHA-256(token). Re-runnable: already-hashed entries are skipped. The 10 s
# refresh:grace:* entries are left to expire. There is no fallback in the app, so sessions not migrated
# here must sign in again.
#
# Usage: REDIS_URL=redis://:password@host:6379/0 ./redis-hash-refresh-keys.sh
set -euo pipefail

rc() { redis-cli ${REDIS_URL:+-u "$REDIS_URL"} --no-auth-warning "$@"; }
sha() { printf '%s' "$1" | shasum -a 256 | cut -c1-64; }
is_hashed() { [[ $1 =~ ^[0-9a-f]{64}$ ]]; }

for ns in refresh refresh:consumed; do
  rc --scan --pattern "$ns:*" | while read -r key; do
    token=${key#"$ns:"}
    # refresh:* also matches refresh:consumed:*, refresh:idx:*, ...: a raw token never contains ':'
    [[ $token == *:* ]] && continue
    is_hashed "$token" && continue
    rc RENAMENX "$key" "$ns:$(sha "$token")" >/dev/null
  done
done

rc --scan --pattern "refresh:idx:*" | while read -r key; do
  rc HKEYS "$key" | while read -r field; do
    is_hashed "$field" && continue
    rc HSET "$key" "$(sha "$field")" 1 >/dev/null
    rc HDEL "$key" "$field" >/dev/null
  done
done
echo "refresh token keys hashed"
