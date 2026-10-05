#!/usr/bin/env bash
#
# Tests for scripts/run_tier_counts.sh. Everything outside the script is faked:
# a python3 stub plays the API and HMPPS Auth, and fake kubectl and psql on PATH
# play Cloud Platform and the database.
#
# Usage:   ./scripts/test/tier_counts_test.sh
# Needs:   bash, jq, curl, python3, node (the pod token snippet runs on Node)
# Exit:    0 if every test passes, 1 if any fail, 2 if a prerequisite is
#          missing. TierCountsScriptTest runs this from JUnit.

set -uo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/run_tier_counts.sh"

for cmd in jq curl python3 node; do
  command -v "$cmd" >/dev/null || { echo "SKIP: $cmd is required" >&2; exit 2; }
done

T="$(mktemp -d)"
STUB_PID=""
cleanup() {
  if [[ -n "$STUB_PID" ]]; then kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; fi
  if [[ -n "${KEEP_TMP:-}" ]]; then echo "kept test files in $T"; else rm -rf "$T"; fi
}
trap cleanup EXIT

passed=0; failed=0; current=""
fail() { echo "    FAIL: $*"; current_failed=1; }
assert_eq() { [[ "$1" == "$2" ]] || fail "$3"$'\n'"      expected: $1"$'\n'"      actual:   $2"; }
assert_file_eq() {
  local actual; actual="$(cat "$2" 2>/dev/null || echo "<missing $2>")"
  [[ "$1" == "$actual" ]] || fail "$3"$'\n'"$(diff <(echo "$1") <(echo "$actual") | sed 's/^/      /')"
}
assert_contains() {
  [[ "$1" == *"$2"* ]] || fail "$3 (no '$2' in output)"$'\n'"$(echo "$1" | tail -5 | sed 's/^/      | /')"
}
run_test() {
  current="$1"; current_failed=0
  echo "  $current"
  "$1"
  if [[ $current_failed -eq 0 ]]; then passed=$((passed + 1)); else failed=$((failed + 1)); fi
}

# ---------------------------------------------------------------------------
# Fakes
# ---------------------------------------------------------------------------
cat > "$T/stub.py" <<'EOF'
import base64, json, os, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

V3 = "https://tier.example/v3/case/"
def header(crn, tier, provisional=None, errors=()):
  return {"crn": crn, "tierScore": tier, "tierProvisional": provisional,
          "tierDetailsLink": V3 + crn, "errors": [{"field": f, "code": c} for f, c in errors]}

CASES = {
  "X000001": header("X000001", "B", False),
  "X000002": header("X000002", "B", True),
  "X000003": header("X000003", "D", False),
  "X000004": header("X000004", "NOT_SUPERVISED"),
  # a MISSING tier and an unknown CRN both reach us as NOT_FOUND
  "X000005": header("X000005", None, errors=[("tierScore", "NOT_FOUND")]),
  "X000006": header("X000006", None, errors=[("tierScore", "NOT_FOUND")]),
  "X000007": header("X000007", "A", False, errors=[("overallRisk", "SERVICE_UNAVAILABLE")]),
  "V2CASE1": {"tierScore": "D2", "tierDetailsLink": "https://tier.example/case/V2CASE1", "errors": []},
}
GOOD_BASIC = "Basic " + base64.b64encode(b"ui-client:s3cr3t").decode()
calls = {}

class Handler(BaseHTTPRequestHandler):
  def reply(self, status, body):
    data = json.dumps(body).encode()
    self.send_response(status)
    self.send_header("Content-Type", "application/json")
    self.send_header("Content-Length", str(len(data)))
    self.end_headers()
    self.wfile.write(data)

  def do_POST(self):
    if self.path.startswith("/auth/oauth/token") and self.headers.get("Authorization") == GOOD_BASIC:
      return self.reply(200, {"access_token": "tok"})
    self.reply(401, {"error": "unauthorized", "error_description": "Bad credentials"})

  def do_GET(self):
    if self.headers.get("Authorization") != "Bearer tok":
      return self.reply(401, {})
    m = re.match(r"/v2/offenders/header/([^/]+)$", self.path)
    crn = m.group(1) if m else None
    calls[crn] = calls.get(crn, 0) + 1
    if crn == "FORBID1":
      return self.reply(403, {})
    if crn == "EXPIRE1" and calls[crn] == 1:   # token expired mid-pass
      return self.reply(401, {})
    if crn == "EXPIRE1":
      return self.reply(200, header(crn, "C", False))
    if crn == "REJECT1":
      return self.reply(200, header(crn, None, errors=[("tierScore", "REQUEST_REJECTED")]))
    if crn == "FLAKY01" and calls[crn] == 1:   # Tier API blip, then fine
      return self.reply(200, header(crn, None, errors=[("tierScore", "SERVICE_UNAVAILABLE")]))
    if crn == "FLAKY01":
      return self.reply(200, header(crn, "B", False))
    if crn == "DOWN001":                        # Tier API down throughout
      return self.reply(200, header(crn, None, errors=[("tierScore", "SERVICE_UNAVAILABLE")]))
    if crn in CASES:
      return self.reply(200, CASES[crn])
    self.reply(404, {})

  def log_message(self, *args):
    pass

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
open(sys.argv[1], "w").write(str(server.server_address[1]))
server.serve_forever()
EOF

python3 "$T/stub.py" "$T/port" &
STUB_PID=$!
for _ in $(seq 1 50); do [[ -s "$T/port" ]] && break; sleep 0.1; done
[[ -s "$T/port" ]] || { echo "ERROR: stub server did not start" >&2; exit 1; }
STUB="http://127.0.0.1:$(cat "$T/port")"

mkdir -p "$T/bin"
cat > "$T/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
echo "kubectl ${*:1:5}" >> "$FAKE_LOG"
b64() { printf %s "$1" | base64; }
case "$*" in
  *"auth can-i"*) exit 0 ;;
  *"get deploy/"*) [[ "$*" == *"deploy/hmpps-esupervision-ui"* ]] ;;
  *" exec "*)
    HMPPS_AUTH_URL="$FAKE_AUTH" CLIENT_CREDS_CLIENT_ID=ui-client \
      CLIENT_CREDS_CLIENT_SECRET="${FAKE_SECRET:-s3cr3t}" node -e "${@: -1}" ;;
  *"get secret hmpps-esupervision-rds-settings"*)
    printf '{"data":{"rds_instance_endpoint":"%s","database_username":"%s","database_name":"%s","database_password":"%s"}}' \
      "$(b64 rds.example:5432)" "$(b64 dbuser)" "$(b64 dbname)" "$(b64 dbpass)" ;;
  *"get secret hmpps-esupervision-ui-client-creds"*)
    printf '{"data":{"CLIENT_CREDS_CLIENT_ID":"%s","CLIENT_CREDS_CLIENT_SECRET":"%s"}}' \
      "$(b64 ui-client)" "$(b64 "${FAKE_SECRET:-s3cr3t}")" ;;
  *" run "*|*" wait "*|*" delete "*) exit 0 ;;
  *"port-forward"*) exec sleep 300 ;;
  *) echo "unexpected kubectl call: $*" >&2; exit 1 ;;
esac
EOF
cat > "$T/bin/psql" <<'EOF'
#!/usr/bin/env bash
echo "psql PGOPTIONS=${PGOPTIONS:-} args=$*" >> "$FAKE_LOG"
case "$*" in
  *"select 1"*) echo 1 ;;
  *"show default_transaction_read_only"*)
    if [[ "${PGOPTIONS:-}" == *"default_transaction_read_only=on"* && "${FAKE_READ_ONLY:-}" != off ]]
    then echo on; else echo off; fi ;;
  *"FROM offender_v2 WHERE status = 'VERIFIED'"*)
    [[ -n "${FAKE_PSQL_FAIL:-}" ]] && { echo "ERROR: relation does not exist" >&2; exit 3; }
    cat "$FAKE_CRNS" ;;
  *) echo "unexpected psql call: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "$T/bin/kubectl" "$T/bin/psql"

printf '%s\n' X000001 X000002 X000003 X000004 X000005 X000006 X000007 > "$T/crns.txt"

run() {  # workdir [extra env...]
  local dir="$1"; shift
  : > "$T/fake.log"
  env PATH="$T/bin:$PATH" FAKE_LOG="$T/fake.log" FAKE_CRNS="$T/crns.txt" FAKE_AUTH="$STUB/auth" \
      EXPORT_API_BASE="$STUB" EXPORT_AUTH_URL="$STUB/auth" RATE_SLEEP=0 "$@" \
      "$SCRIPT" "$dir" 2>&1
}

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
test_counts_each_tier_with_no_zero_rows() {
  local out rc; out="$(run "$T/c1")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  # A-G first, then the rest; C, E, F and G have no CRNs so are absent. An
  # overallRisk error (X000007) does not stop its tier being counted.
  assert_file_eq 'Tier,CRNs
"A",1
"B",2
"D",1
"NOT_SUPERVISED",1
"NO_TIER",2
"Total",7' "$T/c1/tier_counts.csv" "counts"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_selects_only_verified_crns_over_a_read_only_session() {
  run "$T/c2" >/dev/null
  assert_contains "$(cat "$T/fake.log")" "SELECT crn FROM offender_v2 WHERE status = 'VERIFIED'" "query"
  local all ro
  all=$(grep -c '^psql ' "$T/fake.log"); ro=$(grep -c '^psql PGOPTIONS=-c default_transaction_read_only=on ' "$T/fake.log")
  assert_eq "$all" "$ro" "every psql call read-only"
}

test_refuses_to_query_if_the_session_is_not_read_only() {
  local out rc; out="$(run "$T/c3" FAKE_READ_ONLY=off)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is not read-only" "message"
  [[ "$(cat "$T/fake.log")" != *"FROM offender_v2"* ]] || fail "ran the query anyway"
}

test_retries_a_tier_blip_on_the_next_pass() {
  printf '%s\n' X000001 FLAKY01 > "$T/flaky.txt"
  local out rc; out="$(run "$T/c4" FAKE_CRNS="$T/flaky.txt")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_contains "$out" "RETRY crn=FLAKY01 Tier API SERVICE_UNAVAILABLE" "retry logged"
  assert_file_eq 'Tier,CRNs
"B",2
"Total",2' "$T/c4/tier_counts.csv" "counts after retry"
}

test_reports_crns_still_unresolved_and_exits_3() {
  printf '%s\n' X000001 DOWN001 NOSUCH1 > "$T/down.txt"
  local out rc; out="$(run "$T/c5" FAKE_CRNS="$T/down.txt")"; rc=$?
  assert_eq 3 "$rc" "exit code"
  assert_contains "$out" "2 of 3 CRNs are not counted" "warning"
  assert_file_eq $'DOWN001\nNOSUCH1' "$T/c5/unresolved.txt" "unresolved list"
  assert_file_eq 'Tier,CRNs
"B",1
"UNRESOLVED",2
"Total",3' "$T/c5/tier_counts.csv" "unresolved counted separately"
}

test_stops_if_the_api_is_still_on_tier_v2() {
  printf '%s\n' V2CASE1 > "$T/v2.txt"
  local out rc; out="$(run "$T/c6" FAKE_CRNS="$T/v2.txt")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "still reading Tier API v2" "message"
  [[ ! -f "$T/c6/tier_counts.csv" ]] || fail "wrote counts anyway"
}

test_refreshes_the_token_on_a_401_mid_pass() {
  printf '%s\n' X000001 EXPIRE1 > "$T/expire.txt"
  local out rc; out="$(run "$T/c15" FAKE_CRNS="$T/expire.txt")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_eq 2 "$(grep -c 'exec deploy/hmpps-esupervision-ui' "$T/fake.log")" "token fetched for the pass and refreshed once"
  assert_file_eq 'Tier,CRNs
"B",1
"C",1
"Total",2' "$T/c15/tier_counts.csv" "counts"
}

test_stops_when_the_tier_api_rejects_the_apis_credentials() {
  printf '%s\n' REJECT1 X000001 > "$T/reject.txt"
  local out rc; out="$(run "$T/c16" FAKE_CRNS="$T/reject.txt")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "REQUEST_REJECTED" "message"
  [[ ! -f "$T/c16/tiers.jsonl" && ! -f "$T/c16/tier_counts.csv" ]] || fail "carried on past the rejection"
}

test_stops_on_a_403() {
  printf '%s\n' FORBID1 X000001 > "$T/forbid.txt"
  local out rc; out="$(run "$T/c7" FAKE_CRNS="$T/forbid.txt")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "missing ROLE_ESUPERVISION__ESUPERVISION_UI" "message"
}

test_laptop_token_mode_works_and_reports_auth_errors() {
  local out rc; out="$(run "$T/c8" TOKEN_SOURCE=laptop)"; rc=$?
  assert_eq 0 "$rc" "laptop mode exit code"
  out="$(run "$T/c9" TOKEN_SOURCE=laptop FAKE_SECRET=wrong)"
  assert_contains "$out" "HTTP 401 -- unauthorized: Bad credentials" "auth error"
}

test_laptop_token_mode_writes_nothing_to_tmpdir() {
  # Interrupting a run cannot be relied on to hit the moment a temp file holds
  # the token, so check the cause instead: every mktemp must be in the private
  # work dir, which a bare mktemp (in TMPDIR) is not.
  mkdir -p "$T/mktemp-bin"
  cat > "$T/mktemp-bin/mktemp" <<EOF
#!/usr/bin/env bash
echo "mktemp \$*" >> "$T/mktemp.log"
exec $(command -v mktemp) "\$@"
EOF
  chmod +x "$T/mktemp-bin/mktemp"
  : > "$T/mktemp.log"
  run "$T/c-tmp" TOKEN_SOURCE=laptop PATH="$T/mktemp-bin:$T/bin:$PATH" >/dev/null
  local outside
  outside=$(grep -v "^mktemp /.*/c-tmp/\.response\.XXXXXX$" "$T/mktemp.log")
  assert_eq "" "$outside" "temp files created outside the work dir"
}

test_pod_names_are_unique_per_run() {
  local first second
  run "$T/c-pod1" >/dev/null; first=$(awk '$4 == "run" {print $5}' "$T/fake.log")
  run "$T/c-pod2" >/dev/null; second=$(awk '$4 == "run" {print $5}' "$T/fake.log")
  # The PID alone can repeat across machines: the name needs a random part.
  [[ "$first" =~ ^tier-counts-[a-z0-9]+-[0-9]+-[0-9a-f]{8}$ ]] || fail "pod name has no random suffix: $first"
  [[ "$first" != "$second" ]] || fail "pod names not unique: '$first' then '$second'"
  (( ${#first} <= 63 )) || fail "pod name longer than 63 characters: $first"
}

test_pod_token_mode_does_not_read_the_ui_secret() {
  run "$T/c10" >/dev/null
  assert_contains "$(cat "$T/fake.log")" "exec deploy/hmpps-esupervision-ui" "token from the pod"
  [[ "$(cat "$T/fake.log")" != *"ui-client-creds"* ]] || fail "pod mode read the UI client secret"
}

test_rerun_starts_afresh() {
  run "$T/c11" >/dev/null
  run "$T/c11" >/dev/null
  assert_eq 7 "$(wc -l < "$T/c11/tiers.jsonl" | tr -d ' ')" "one result per CRN, not two"
}

test_work_dir_and_files_are_private() {
  (umask 022; run "$T/c12" >/dev/null)
  assert_eq "drwx------" "$(ls -ld "$T/c12" | cut -c1-10)" "work dir permissions"
  local f
  for f in tier_counts.csv tiers.jsonl active_crns.txt; do
    assert_eq "-rw-------" "$(ls -l "$T/c12/$f" | cut -c1-10)" "$f permissions"
  done
}

test_refuses_a_work_dir_inside_the_repo() {
  local out rc; out="$(run "$REPO_ROOT/build/tier-counts-test")"; rc=$?
  rmdir "$REPO_ROOT/build/tier-counts-test" 2>/dev/null
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is inside the repo" "message"
}

test_deletes_the_pod_when_the_sql_step_fails() {
  local out rc; out="$(run "$T/c13" FAKE_PSQL_FAIL=1)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "SQL step failed" "message"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_rejects_a_bad_passes_value() {
  local out rc; out="$(run "$T/c14" PASSES=0)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "PASSES must be a positive whole number" "message"
}

# ---------------------------------------------------------------------------
echo "tier counts script"
for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  run_test "$t"
done
echo
echo "$passed passed, $failed failed"
[[ $failed -eq 0 ]]
