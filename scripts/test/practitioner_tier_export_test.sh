#!/usr/bin/env bash
#
# Tests for scripts/run_practitioner_tier_export.sh. Everything outside the
# script is faked: a python3 stub plays the API and HMPPS Auth, and fake kubectl
# and psql on PATH play Cloud Platform and the database.
#
# Usage:   ./scripts/test/practitioner_tier_export_test.sh
# Needs:   bash, jq, curl, python3, node (the pod token snippet runs on Node)
# Exit:    0 if every test passes, 1 if any fail, 2 if a prerequisite is
#          missing. PractitionerTierExportScriptTest runs this from JUnit.

set -uo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/run_practitioner_tier_export.sh"

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
import base64, json, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def header(crn, tier=None, error=None, provisional=None):
  return {"crn": crn, "dateOfBirth": "1990-01-01", "tierScore": tier, "tierProvisional": provisional,
          "tierDetailsLink": "https://tier.example/" + crn, "overallRisk": "HIGH",
          "errors": [] if error is None else [{"field": "tierScore", "code": error}]}

CASES = {
  "X000001": header("X000001", "B"),
  "X000002": header("X000002", "C", provisional=True),
  "X000003": header("X000003", "B"),
  "X000004": header("X000004", error="NOT_FOUND"),
  "X000005": header("X000005", "NOT_SUPERVISED"),
  "X000006": header("X000006", "D2"),
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
    m = re.match(r"/v2/offenders/header/([^/?]+)$", self.path)
    if not m:
      return self.reply(400, {"error": "unexpected path " + self.path})
    crn = m.group(1)
    calls[crn] = calls.get(crn, 0) + 1
    if crn == "FORBID1":
      return self.reply(403, {})
    if crn == "EXPIRE1" and calls[crn] == 1:   # token expired mid-pass
      return self.reply(401, {})
    if crn == "EXPIRE1":
      return self.reply(200, header(crn, "A"))
    if crn == "FLAKY01" and calls[crn] == 1:   # Tier API blip, then fine
      return self.reply(200, header(crn, error="SERVICE_UNAVAILABLE"))
    if crn == "FLAKY01":
      return self.reply(200, header(crn, "E"))
    if crn == "DOWN001":                        # Tier API down throughout
      return self.reply(200, header(crn, error="SERVICE_UNAVAILABLE"))
    if crn == "NOSHAPE":                        # 200, but not the shape we expect
      return self.reply(200, {"oops": True})
    if crn == "ERROR01":
      return self.reply(500, {})
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
[[ -z "${PGPASSWORD:-}" ]] || echo "PGPASSWORD seen by kubectl ${*:1:5}" >> "$FAKE_LOG"
b64() { printf %s "$1" | base64; }
case "$*" in
  *"auth can-i"*) [[ -z "${FAKE_DENY:-}" || "$*" != *"can-i $FAKE_DENY"* ]] ;;
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
[[ "$*" == --version ]] && { echo "psql (PostgreSQL) ${FAKE_PSQL_VERSION:-18.3}"; exit 0; }
echo "psql TSA=${PGTARGETSESSIONATTRS:-} PGOPTIONS=${PGOPTIONS:-} args=$*" >> "$FAKE_LOG"
# The session is read-only when the server applied PGOPTIONS (FAKE_READ_ONLY=off
# plays a pooler that stripped it). Like libpq, refuse to connect at all when
# target_session_attrs=read-only and the session is not.
if [[ "${PGOPTIONS:-}" == *"default_transaction_read_only=on"* && "${FAKE_READ_ONLY:-}" != off ]]
then ro=on; else ro=off; fi
if [[ "${PGTARGETSESSIONATTRS:-}" == read-only && $ro == off ]]; then
  echo 'psql: error: connection to server at "127.0.0.1", port 5433 failed: session is not read-only' >&2
  exit 2
fi
case "$*" in
  *"select 1"*) echo 1 ;;
  *"show default_transaction_read_only"*) echo "$ro" ;;
  *"FROM event_audit_log_v2"*)
    [[ -n "${FAKE_PSQL_FAIL:-}" ]] && { echo "ERROR: relation does not exist" >&2; exit 3; }
    [[ -n "${FAKE_TXN_NOT_RO:-}" ]] && { echo "ERROR:  session is not read-only -- refusing to run the query" >&2; exit 3; }
    cat "$FAKE_ROWS" ;;
  *) echo "unexpected psql call: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "$T/bin/kubectl" "$T/bin/psql"

# What the query returns: one JSON object per practitioner row, in its order.
# X000003 sits under two PDUs, as a CRN with more than one audited PDU does.
cat > "$T/rows.jsonl" <<'EOF'
{"pduCode":"PDU1","pdu":"Alpha PDU","regions":"Region One","practitionerId":"ANN.SMITH","crns":["X000001","X000002","X000003"]}
{"pduCode":"PDU1","pdu":"Alpha PDU","regions":"Region One, Region Two","practitionerId":"BOB.JONES","crns":["X000004"]}
{"pduCode":"PDU2","pdu":"Beta PDU","regions":"Region Two","practitionerId":"ANN.SMITH","crns":["X000003","X000005","X000006"]}
EOF

rows_for() {  # file crn...
  local f="$1"; shift
  jq -cn --args '{pduCode: "PDU1", pdu: "Alpha PDU", regions: "Region One", practitionerId: "ANN.SMITH", crns: $ARGS.positional}' "$@" > "$f"
}

run() {  # workdir [extra env...]
  local dir="$1"; shift
  : > "$T/fake.log"
  env PATH="$T/bin:$PATH" FAKE_LOG="$T/fake.log" FAKE_ROWS="$T/rows.jsonl" FAKE_AUTH="$STUB/auth" \
      EXPORT_API_BASE="$STUB" EXPORT_AUTH_URL="$STUB/auth" RATE_SLEEP=0 "$@" \
      "$SCRIPT" "$dir" 2>&1
}

expect_exit() {  # code command...
  local want="$1"; shift
  local out rc=0
  out="$("$@")" || rc=$?
  [[ "$rc" == "$want" ]] || fail "$* exited $rc, expected $want"$'\n'"$(echo "$out" | tail -5 | sed 's/^/      | /')"
}
run_umask_022() { (umask 022; run "$@"); }

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
test_adds_each_crns_tier_to_the_practitioner_rows() {
  local out rc; out="$(run "$T/c1")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_file_eq '"PDU code","PDU","Regions","Practitioner ID","CRNs","Pop count","Tier counts"
"PDU1","Alpha PDU","Region One","ANN.SMITH","X000001 (B), X000002 (C), X000003 (B)",3,"B: 2, C: 1"
"PDU1","Alpha PDU","Region One, Region Two","BOB.JONES","X000004 (None)",1,"None: 1"
"PDU2","Beta PDU","Region Two","ANN.SMITH","X000003 (B), X000005 (NOT_SUPERVISED), X000006 (D2)",3,"B: 1, D2: 1, NOT_SUPERVISED: 1"' \
    "$T/c1/practitioner_tiers.csv" "export"
  assert_file_eq '"PDU code","PDU","Practitioner ID","CRN","Tier"
"PDU1","Alpha PDU","ANN.SMITH","X000001","B"
"PDU1","Alpha PDU","ANN.SMITH","X000002","C"
"PDU1","Alpha PDU","ANN.SMITH","X000003","B"
"PDU1","Alpha PDU","BOB.JONES","X000004","None"
"PDU2","Beta PDU","ANN.SMITH","X000003","B"
"PDU2","Beta PDU","ANN.SMITH","X000005","NOT_SUPERVISED"
"PDU2","Beta PDU","ANN.SMITH","X000006","D2"' "$T/c1/crn_tiers.csv" "per-CRN export"
  [[ ! -e "$T/c1/practitioner_tiers.PARTIAL.csv" ]] || fail "complete run wrote a PARTIAL export"
  assert_contains "$out" "Distinct CRNs:     6" "summary"
  assert_contains "$out" "Tier B: 2" "tier summary"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_fetches_each_crn_once_even_when_it_is_under_two_pdus() {
  local out rc; out="$(run "$T/c-once")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_contains "$out" "pass 1: requested=6 unresolved=0" "six distinct CRNs requested"
}

test_every_connection_is_forced_read_only() {
  expect_exit 0 run "$T/c-forced"
  local all forced
  all=$(grep -c '^psql ' "$T/fake.log")
  forced=$(grep -c '^psql TSA=read-only ' "$T/fake.log")
  (( all > 0 )) || fail "no psql calls logged"
  assert_eq "$all" "$forced" "psql calls without target_session_attrs=read-only"
}

test_refuses_at_connect_when_the_server_does_not_make_the_session_read_only() {
  local out rc start; start=$SECONDS
  out="$(run "$T/c-strip" FAKE_READ_ONLY=off)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "refused a read-only connection" "message"
  (( SECONDS - start < 10 )) || fail "kept retrying the connection instead of stopping at once"
  assert_eq 1 "$(grep -c '^psql ' "$T/fake.log")" "psql calls after the refusal"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_requires_a_psql_that_can_force_read_only() {
  local out rc; out="$(run "$T/c-oldpsql" FAKE_PSQL_VERSION=13.9)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "psql 14 or later is required" "message"
  ! grep -q "kubectl .* run " "$T/fake.log" || fail "a pod was created"
}

test_every_psql_call_skips_psqlrc() {
  expect_exit 0 run "$T/c-psqlrc"
  local all nox
  all=$(grep -c '^psql ' "$T/fake.log")
  nox=$(grep '^psql ' "$T/fake.log" | grep -vc ' args=-X ')
  (( all > 0 )) || fail "no psql calls logged"
  assert_eq 0 "$nox" "psql calls without -X"
}

test_query_runs_in_a_read_only_transaction_that_is_rolled_back() {
  expect_exit 0 run "$T/c-txn"
  local call; call="$(awk '/^psql .*BEGIN TRANSACTION READ ONLY/{f=1} f{print} /ROLLBACK/{if(f)exit}' "$T/fake.log")"
  assert_contains "$call" "-c BEGIN TRANSACTION READ ONLY -c DO" "transaction opened read-only, then checked"
  assert_contains "$call" "current_setting('transaction_read_only') <> 'on'" "in-session read-only check"
  assert_contains "$call" "FROM event_audit_log_v2" "query inside the transaction"
  assert_contains "$call" "-c ROLLBACK" "transaction rolled back"
  [[ "$(cat "$T/fake.log")" != *COMMIT* ]] || fail "something was committed"
}

test_sends_no_writing_sql_at_all() {
  expect_exit 0 run "$T/c-nowrite"
  local sql; sql="$(grep -v '^kubectl \|^PGPASSWORD ' "$T/fake.log" | sed 's/^psql TSA=[^ ]* PGOPTIONS=[^ ]* [^ ]* args=//')"
  local word
  for word in INSERT UPDATE DELETE MERGE UPSERT CREATE ALTER DROP TRUNCATE GRANT REVOKE COPY VACUUM REINDEX \
              CLUSTER LOCK COMMIT "SELECT INTO" nextval setval pg_terminate pg_cancel lo_; do
    grep -qi -- "$word" <<<"$sql" && fail "SQL sent contains $word"
  done
  # And the only statements are the known ones.
  local unknown
  unknown=$(grep '^psql ' "$T/fake.log" | sed 's/^psql TSA=[^ ]* PGOPTIONS=[^ ]* [^ ]* args=//' \
    | grep -v '^-X -qtAc select 1$' | grep -v '^-X -qtAc show default_transaction_read_only$' \
    | grep -v '^-X -qtA -v ON_ERROR_STOP=1 -c BEGIN TRANSACTION READ ONLY -c DO ')
  assert_eq "" "$unknown" "unexpected psql calls"
}

test_stops_if_the_query_transaction_is_not_read_only() {
  local out rc; out="$(run "$T/c-notro" FAKE_TXN_NOT_RO=1)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "SQL step failed" "message"
  [[ -z "$(ls "$T/c-notro" | grep '\.csv$')" ]] || fail "wrote an export anyway"
  ! grep -q "exec deploy" "$T/fake.log" || fail "carried on to fetch tiers"
}

test_does_not_request_a_malformed_crn() {
  rows_for "$T/bad.jsonl" X000001 "../admin" "X 1"
  local out rc; out="$(run "$T/c-bad" FAKE_ROWS="$T/bad.jsonl" PASSES=1)"; rc=$?
  assert_eq 3 "$rc" "exit code"
  assert_contains "$out" "RETRY crn=../admin not a valid CRN, not requested" "malformed CRN skipped"
  assert_contains "$(cat "$T/c-bad/practitioner_tiers.PARTIAL.csv")" "X000001 (B)" "good CRN still exported"
}

test_runs_the_stats_query_over_a_read_only_session() {
  expect_exit 0 run "$T/c4"
  local log; log="$(cat "$T/fake.log")"
  assert_contains "$log" "WHERE ealv.pdu_code != 'XXX001' AND ov.status = 'VERIFIED'" "filters"
  assert_contains "$log" "GROUP BY UPPER(ov.practitioner_id), ealv.pdu_description, ealv.pdu_code" "grouping"
  local all ro
  all=$(grep -c '^psql ' "$T/fake.log"); ro=$(grep -c '^psql TSA=read-only PGOPTIONS=-c default_transaction_read_only=on ' "$T/fake.log")
  assert_eq "$all" "$ro" "every psql call read-only"
}

test_refuses_to_query_if_the_session_is_not_read_only() {
  local out rc; out="$(run "$T/c5" FAKE_READ_ONLY=off)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is not read-only" "message"
  [[ "$(cat "$T/fake.log")" != *"FROM event_audit_log_v2"* ]] || fail "ran the query anyway"
}

test_retries_a_tier_api_blip_on_the_next_pass() {
  rows_for "$T/flaky.jsonl" X000001 FLAKY01
  local out rc; out="$(run "$T/c6" FAKE_ROWS="$T/flaky.jsonl")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_contains "$out" "RETRY crn=FLAKY01 Tier API SERVICE_UNAVAILABLE" "retry logged"
  assert_file_eq '"PDU code","PDU","Regions","Practitioner ID","CRNs","Pop count","Tier counts"
"PDU1","Alpha PDU","Region One","ANN.SMITH","X000001 (B), FLAKY01 (E)",2,"B: 1, E: 1"' \
    "$T/c6/practitioner_tiers.csv" "export after retry"
}

test_marks_unresolved_tiers_unknown_and_exits_3() {
  rows_for "$T/down.jsonl" X000001 DOWN001 ERROR01 NOSHAPE NOSUCH1
  local out rc; out="$(run "$T/c7" FAKE_ROWS="$T/down.jsonl")"; rc=$?
  assert_eq 3 "$rc" "exit code"
  assert_contains "$out" "4 of 5 CRNs have no tier in the export" "warning"
  assert_contains "$out" "RETRY crn=NOSHAPE unexpected response" "unexpected shape retried"
  assert_contains "$out" "RETRY crn=ERROR01 HTTP 500" "server error retried"
  assert_contains "$out" "RETRY crn=NOSUCH1 CRN not found in NDelius" "404 retried"
  assert_file_eq $'DOWN001\nERROR01\nNOSHAPE\nNOSUCH1' "$T/c7/unresolved.txt" "unresolved list"
  assert_file_eq '"PDU code","PDU","Regions","Practitioner ID","CRNs","Pop count","Tier counts"
"PDU1","Alpha PDU","Region One","ANN.SMITH","X000001 (B), DOWN001 (Unknown), ERROR01 (Unknown), NOSHAPE (Unknown), NOSUCH1 (Unknown)",5,"B: 1, Unknown: 4"' \
    "$T/c7/practitioner_tiers.PARTIAL.csv" "partial export"
  [[ -e "$T/c7/crn_tiers.PARTIAL.csv" ]] || fail "no partial per-CRN export"
  [[ ! -e "$T/c7/practitioner_tiers.csv" && ! -e "$T/c7/crn_tiers.csv" ]] || fail "partial run wrote a complete-looking export"
}

test_refreshes_the_token_on_a_401_mid_pass() {
  rows_for "$T/expire.jsonl" X000001 EXPIRE1
  local out rc; out="$(run "$T/c8" FAKE_ROWS="$T/expire.jsonl")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_eq 2 "$(grep -c '^kubectl .*exec deploy/hmpps-esupervision-ui' "$T/fake.log")" "token fetched for the pass and refreshed once"
  assert_contains "$(cat "$T/c8/practitioner_tiers.csv")" "EXPIRE1 (A)" "tier after refresh"
}

test_stops_on_a_403() {
  rows_for "$T/forbid.jsonl" FORBID1 X000001
  local out rc; out="$(run "$T/c9" FAKE_ROWS="$T/forbid.jsonl")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "missing ROLE_ESUPERVISION__ESUPERVISION_UI" "message"
  [[ -z "$(ls "$T/c9" | grep '\.csv$')" ]] || fail "wrote an export anyway"
}

test_laptop_token_mode_works_and_reports_auth_errors() {
  local out rc; out="$(run "$T/c10" TOKEN_SOURCE=laptop)"; rc=$?
  assert_eq 0 "$rc" "laptop mode exit code"
  out="$(run "$T/c11" TOKEN_SOURCE=laptop FAKE_SECRET=wrong)"; rc=$?
  assert_eq 1 "$rc" "bad secret exit code"
  assert_contains "$out" "HTTP 401 -- unauthorized: Bad credentials" "auth error"
}

test_pod_token_mode_does_not_read_the_ui_secret() {
  expect_exit 0 run "$T/c12"
  assert_contains "$(cat "$T/fake.log")" "exec deploy/hmpps-esupervision-ui" "token from the pod"
  [[ "$(cat "$T/fake.log")" != *"ui-client-creds"* ]] || fail "pod mode read the UI client secret"
}

test_leaves_only_the_deliverables_behind() {
  expect_exit 0 run "$T/c3"
  assert_eq $'crn_tiers.csv\npractitioner_tiers.csv' "$(ls -A "$T/c3")" "work dir contents"
}

test_stops_before_creating_a_pod_it_could_not_delete() {
  local out rc=0
  out="$(run "$T/nodelete" FAKE_DENY="delete pods")" || rc=$?
  [[ $rc -ne 0 ]] || fail "run succeeded without delete permission"
  assert_contains "$out" "cannot delete pods" "names the missing permission"
  ! grep -q "kubectl .* run " "$T/fake.log" || fail "a pod was created"
}

test_a_failed_preflight_leaves_no_earlier_results_behind() {
  mkdir -p "$T/stale"
  echo "last week" > "$T/stale/practitioner_tiers.csv"
  local rc=0
  run "$T/stale" FAKE_DENY="create pods" >/dev/null || rc=$?
  [[ $rc -ne 0 ]] || fail "run succeeded without create permission"
  [[ ! -e "$T/stale/practitioner_tiers.csv" ]] || fail "an earlier run's export was left in place"
}

test_the_database_password_is_not_passed_on_after_the_sql_step() {
  expect_exit 0 run "$T/pgenv"
  grep -q "^PGPASSWORD seen by kubectl .*port-forward" "$T/fake.log" || fail "fake did not detect PGPASSWORD at all"
  local late
  late=$(grep "^PGPASSWORD seen by kubectl .* \(exec\|delete\) " "$T/fake.log")
  assert_eq "" "$late" "PGPASSWORD still set for later commands"
}

test_pod_names_are_unique_and_valid() {
  local first second
  expect_exit 0 run "$T/c-pod1"; first=$(awk '$4 == "run" {print $5}' "$T/fake.log")
  expect_exit 0 run "$T/c-pod2"; second=$(awk '$4 == "run" {print $5}' "$T/fake.log")
  [[ "$first" =~ ^practitioner-tier-export-[a-z0-9]+-[0-9]+-[0-9a-f]{8}$ ]] || fail "unexpected pod name: $first"
  [[ "$first" != "$second" ]] || fail "pod names not unique: '$first' then '$second'"
  (( ${#first} <= 63 )) || fail "pod name longer than 63 characters: $first"
}

test_work_dir_and_files_are_private() {
  expect_exit 0 run_umask_022 "$T/c14"
  assert_eq "drwx------" "$(ls -ld "$T/c14" | cut -c1-10)" "work dir permissions"
  local f
  for f in practitioner_tiers.csv crn_tiers.csv; do
    assert_eq "-rw-------" "$(ls -l "$T/c14/$f" | cut -c1-10)" "$f permissions"
  done
}

test_refuses_a_work_dir_inside_the_repo() {
  local out rc; out="$(run "$REPO_ROOT/build/practitioner-tier-export-test")"; rc=$?
  rmdir "$REPO_ROOT/build/practitioner-tier-export-test" 2>/dev/null
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is inside the repo" "message"
}

test_deletes_the_pod_when_the_sql_step_fails() {
  local out rc; out="$(run "$T/c15" FAKE_PSQL_FAIL=1)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "SQL step failed" "message"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_stops_when_the_query_returns_nothing() {
  : > "$T/empty.jsonl"
  local out rc; out="$(run "$T/c-empty" FAKE_ROWS="$T/empty.jsonl")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "returned no CRNs" "message"
}

test_rejects_a_bad_passes_value() {
  local out rc; out="$(run "$T/c16" PASSES=0)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "PASSES must be a positive whole number" "message"
}

# ---------------------------------------------------------------------------
echo "practitioner tier export script"
for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  run_test "$t"
done
echo
echo "$passed passed, $failed failed"
[[ $failed -eq 0 ]]
