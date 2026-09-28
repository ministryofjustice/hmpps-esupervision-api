#!/usr/bin/env bash
#
# Tests for scripts/fetch_practitioner_details.sh and scripts/run_practitioner_export.sh.
#
# Everything outside the scripts is faked, so this touches no cluster, database or
# real API:
#   - a stub HTTP server (python3) plays the API and HMPPS Auth
#   - fake kubectl and psql on PATH play Cloud Platform and the database
# The SQL step itself is tested separately, against the real migrated schema, by
# PractitionerContactListSqlTest.
#
# Usage:   ./scripts/test/practitioner_export_test.sh
# Needs:   bash, jq, curl, python3, node (the pod token snippet runs on Node 24)
# Exit:    0 if every test passes, 1 otherwise. PractitionerExportScriptsTest runs
#          this from JUnit, so it also runs in `./gradlew test`.

set -uo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
FETCH="$REPO_ROOT/scripts/fetch_practitioner_details.sh"
WRAPPER="$REPO_ROOT/scripts/run_practitioner_export.sh"

for cmd in jq curl python3 node; do
  command -v "$cmd" >/dev/null || { echo "SKIP: $cmd is required" >&2; exit 2; }
done

T="$(mktemp -d)"
STUB_PID=""
cleanup() {
  if [[ -n "$STUB_PID" ]]; then kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; fi
  rm -rf "$T"
}
trap cleanup EXIT

passed=0; failed=0; current=""

# ---------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------
fail() { echo "    FAIL: $*"; current_failed=1; }

assert_eq() {  # expected actual message
  [[ "$1" == "$2" ]] || fail "$3"$'\n'"      expected: $1"$'\n'"      actual:   $2"
}

assert_file_eq() {  # expected-content file message
  local actual; actual="$(cat "$2" 2>/dev/null || echo "<missing $2>")"
  [[ "$1" == "$actual" ]] || fail "$3"$'\n'"$(diff <(echo "$1") <(echo "$actual") | sed 's/^/      /')"
}

assert_contains() {  # haystack needle message
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
# The API and HMPPS Auth. Binds a free port and writes it to a file, so parallel
# runs and CI agents never collide.
cat > "$T/stub.py" <<'EOF'
import base64, json, re, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

CASES = {
  # two cases for one practitioner, in different PDUs, emails in mixed case
  "X000001": {"name": {"forename": "BARRY", "surname": "WHITE"}, "email": "BARRY.WHITE@justice.gov.uk",
              "username": "BARRY.WHITE", "unallocated": False,
              "probationDeliveryUnit": {"code": "N50", "description": "Cumbria, and Lancashire PDU"}},
  "X000002": {"name": {"forename": "Barry", "surname": "White"}, "email": "barry.white@JUSTICE.gov.uk",
              "username": "BARRY.WHITE", "unallocated": False,
              "probationDeliveryUnit": {"code": "N51", "description": "Salford PDU"}},
  # reallocated since setup: stored OLD.OWNER, now ANNE.OBRIEN
  "X000003": {"name": {"forename": "Anne", "surname": "Obrien"}, "email": "anne.obrien@justice.gov.uk",
              "username": "ANNE.OBRIEN", "unallocated": False,
              "probationDeliveryUnit": {"code": "N51", "description": "Salford PDU"}},
  # NDelius knows the practitioner but holds no email
  "X000004": {"name": {"forename": "Jo", "surname": "Bloggs"}, "username": "JO.BLOGGS", "unallocated": False,
              "probationDeliveryUnit": {"code": "N50", "description": "Cumbria, and Lancashire PDU"}},
  # X000005 is absent: 404
  # unallocated-staff placeholder, whose username differs from the one that set the case up
  "X000006": {"name": {"forename": "Unallocated", "surname": "Staff"}, "email": "unallocated@justice.gov.uk",
              "username": "UNALLOCATED.STAFF", "unallocated": True,
              "probationDeliveryUnit": {"code": "N52", "description": "Wigan PDU"}},
  # no live PDU: the snapshot must be used
  "X000007": {"name": {"forename": "Sam", "surname": "Patel"}, "email": "sam.patel@justice.gov.uk",
              "username": "SAM.PATEL", "unallocated": False},
}
GOOD_BASIC = "Basic " + base64.b64encode(b"ui-client:s3cr3t").decode()

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
    m = re.match(r"/v2/offenders/crn/([^/]+)/practitioner-details$", self.path)
    crn = m.group(1) if m else None
    if crn == "FORBID1":
      return self.reply(403, {})
    if crn in CASES:
      return self.reply(200, CASES[crn])
    self.reply(404, {})

  def log_message(self, *args):
    pass

server = HTTPServer(("127.0.0.1", 0), Handler)
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
# Logs the verb and target only -- never the node snippet passed to exec.
echo "kubectl ${*:1:5}" >> "$FAKE_LOG"
b64() { printf %s "$1" | base64; }
case "$*" in
  *"auth can-i"*) exit 0 ;;
  *"get deploy/"*) [[ "$*" == *"deploy/hmpps-esupervision-ui"* ]] ;;
  *" exec "*)  # run the real snippet, as the pod would, against the stub
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
echo "psql cwd=$PWD PGHOST=$PGHOST PGPORT=$PGPORT PGUSER=$PGUSER" >> "$FAKE_LOG"
case "$*" in
  *"select 1"*) echo 1 ;;
  *"-f "*)
    [[ -n "${FAKE_PSQL_FAIL:-}" ]] && { echo "ERROR: relation does not exist"; exit 3; }
    cp "$FAKE_CRNS" practitioner_crns.jsonl   # what the SQL's \o writes
    printf 'username,mentions,sources\n' > practitioner_usernames.csv ;;
esac
EOF
chmod +x "$T/bin/kubectl" "$T/bin/psql"

# The CRN file the SQL step would produce. X000006 was set up by SETUP.PERSON and
# is now unallocated; X000003 was set up by OLD.OWNER and has since moved.
cat > "$T/crns.jsonl" <<'EOF'
{"crn":"X000001","storedUsername":"BARRY.WHITE","status":"VERIFIED","pdu":"Cumbria, and Lancashire PDU","region":"North West"}
{"crn":"X000002","storedUsername":"BARRY.WHITE","status":"VERIFIED","pdu":"Cumbria, and Lancashire PDU","region":"North West"}
{"crn":"X000003","storedUsername":"OLD.OWNER","status":"INACTIVE","pdu":"Bolton PDU","region":"North West"}
{"crn":"X000004","storedUsername":"JO.BLOGGS","status":"VERIFIED","pdu":"Cumbria, and Lancashire PDU","region":"North West"}
{"crn":"X000005","storedUsername":"GONE.AWAY","status":"INITIAL","pdu":null,"region":null}
{"crn":"X000006","storedUsername":"SETUP.PERSON","status":"INITIAL","pdu":"Wigan PDU","region":"North West"}
{"crn":"X000007","storedUsername":"SAM.PATEL","status":"VERIFIED","pdu":"Lambeth PDU","region":"London"}
EOF

EXPECTED_EXPORT='PDU,Region,CRN,POP count,Email address
"Lambeth PDU","London","X000007",1,"sam.patel@justice.gov.uk"
"Cumbria, and Lancashire PDU; Salford PDU","North West","X000001; X000002",2,"barry.white@justice.gov.uk"
"Salford PDU","North West","X000003",1,"anne.obrien@justice.gov.uk"
"Cumbria, and Lancashire PDU","North West","X000004",1,""
"Wigan PDU","North West","X000006",1,""
"","","X000005",1,""'

EXPECTED_WORKSHEET='username,PDU,Region,CRN,POP count
"GONE.AWAY","","","X000005",1
"OLD.OWNER","Bolton PDU","North West","X000003",1
"JO.BLOGGS","Cumbria, and Lancashire PDU","North West","X000004",1
"SETUP.PERSON","Wigan PDU","North West","X000006",1'

# Runs the fetch script in a fresh directory, from a DIFFERENT cwd, so the tests
# also prove that outputs follow the input rather than the working directory.
fetch() {  # dir [extra env...]
  local dir="$1"; shift
  mkdir -p "$dir" "$T/elsewhere"
  [[ -f "$dir/crns.jsonl" ]] || cp "$T/crns.jsonl" "$dir/crns.jsonl"
  (cd "$T/elsewhere" && env TOKEN=tok API_BASE="$STUB" RATE_SLEEP=0 "$@" \
     "$FETCH" "$dir/crns.jsonl" "$dir/results.jsonl" 2>&1)
}

wrapper() {  # workdir [extra env...]
  local dir="$1"; shift
  : > "$T/fake.log"
  env PATH="$T/bin:$PATH" FAKE_LOG="$T/fake.log" FAKE_CRNS="$T/crns.jsonl" FAKE_AUTH="$STUB/auth" \
      EXPORT_API_BASE="$STUB" EXPORT_AUTH_URL="$STUB/auth" RATE_SLEEP=0 PASSES=1 "$@" \
      "$WRAPPER" "$dir" 2>&1
}

# ---------------------------------------------------------------------------
# fetch_practitioner_details.sh
# ---------------------------------------------------------------------------
test_export_has_one_row_per_practitioner_with_the_requested_columns() {
  fetch "$T/f1" >/dev/null
  # Covers, in one file: the exact header; a practitioner's CRNs and PDUs
  # collapsed into one row with POP count 2; emails lower-cased; live PDU
  # preferred, snapshot used when the live one is missing (X000007); commas in
  # a PDU name surviving CSV quoting; blank-email rows kept and sorted last.
  assert_file_eq "$EXPECTED_EXPORT" "$T/f1/practitioner_export.csv" "export content"
}

test_worksheet_names_every_practitioner_without_an_email() {
  fetch "$T/f2" >/dev/null
  # Includes OLD.OWNER, who is absent from the export because the case moved.
  assert_file_eq "$EXPECTED_WORKSHEET" "$T/f2/practitioners_unmatched.csv" "worksheet content"
}

test_outputs_are_written_beside_the_input_not_the_working_directory() {
  fetch "$T/f3" >/dev/null
  [[ -f "$T/f3/practitioner_export.csv" ]] || fail "export not beside the input"
  [[ ! -e "$T/elsewhere/practitioner_export.csv" ]] || fail "export leaked into the working directory"
}

test_summary_does_not_count_unallocated_placeholders_as_reallocations() {
  local out; out="$(fetch "$T/f4")"
  assert_contains "$out" "unallocated=1 reallocated_since_setup=1" "reallocation count"
}

test_rerun_skips_fetched_crns_and_retries_404s() {
  fetch "$T/f5" >/dev/null
  local out; out="$(fetch "$T/f5")"
  assert_contains "$out" "rows=7 fetched=0 failed=1 already_present=6" "resume counts"
  assert_file_eq "$EXPECTED_EXPORT" "$T/f5/practitioner_export.csv" "export unchanged by a re-run"
}

test_stops_on_a_403_rather_than_fetching_the_rest() {
  mkdir -p "$T/f6"
  printf '%s\n' '{"crn":"FORBID1","storedUsername":"A.B"}' '{"crn":"X000001","storedUsername":"BARRY.WHITE"}' > "$T/f6/crns.jsonl"
  local out rc; out="$(fetch "$T/f6")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "missing ROLE_ESUPERVISION__ESUPERVISION_UI" "auth message"
  assert_eq 1 "$(wc -l < "$T/f6/results.jsonl" | tr -d ' ')" "only the 403 row recorded"
}

test_stops_on_a_401() {
  mkdir -p "$T/f7"; cp "$T/crns.jsonl" "$T/f7/crns.jsonl"
  local rc; (cd "$T" && TOKEN=expired API_BASE="$STUB" RATE_SLEEP=0 "$FETCH" "$T/f7/crns.jsonl" "$T/f7/results.jsonl" >/dev/null 2>&1); rc=$?
  assert_eq 1 "$rc" "exit code"
}

test_records_a_transport_failure_as_http_000() {
  mkdir -p "$T/f8"; head -1 "$T/crns.jsonl" > "$T/f8/crns.jsonl"
  # Port 1: nothing listens, so the connection is refused.
  (cd "$T" && TOKEN=tok API_BASE="http://127.0.0.1:1" RATE_SLEEP=0 "$FETCH" "$T/f8/crns.jsonl" "$T/f8/results.jsonl" >/dev/null 2>&1)
  assert_eq '"HTTP 000"' "$(jq -c .error "$T/f8/results.jsonl")" "error recorded"
}

# ---------------------------------------------------------------------------
# run_practitioner_export.sh
# ---------------------------------------------------------------------------
test_wrapper_runs_end_to_end_and_deletes_the_pod() {
  local out; out="$(wrapper "$T/w1")"
  assert_file_eq "$EXPECTED_EXPORT" "$T/w1/practitioner_export.csv" "export content"
  [[ -f "$T/w1/sql_report.txt" ]] || fail "no sql_report.txt"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
  assert_contains "$(cat "$T/fake.log")" "psql cwd=$(cd "$T/w1" && pwd -P)" "SQL run from the work dir"
  assert_contains "$(cat "$T/fake.log")" "PGHOST=127.0.0.1 PGPORT=5433 PGUSER=dbuser" "psql connection env"
}

test_wrapper_gets_the_token_in_the_pod_without_reading_the_ui_secret() {
  wrapper "$T/w2" >/dev/null
  assert_contains "$(cat "$T/fake.log")" "exec deploy/hmpps-esupervision-ui" "token from the pod"
  [[ "$(cat "$T/fake.log")" != *"ui-client-creds"* ]] || fail "pod mode read the UI client secret"
}

test_wrapper_refuses_a_work_dir_inside_the_repo() {
  local out rc; out="$(wrapper "$REPO_ROOT/build/export-test")"; rc=$?
  rmdir "$REPO_ROOT/build/export-test" 2>/dev/null
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is inside the repo" "message"
}

test_wrapper_deletes_the_pod_when_the_sql_step_fails() {
  local out rc; out="$(wrapper "$T/w3" FAKE_PSQL_FAIL=1)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "SQL step failed" "message"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_wrapper_shows_hmpps_auths_answer_when_the_pod_token_is_refused() {
  local out; out="$(wrapper "$T/w4" FAKE_SECRET=wrong)"
  assert_contains "$out" "HTTP 401" "status"
  assert_contains "$out" "Bad credentials" "HMPPS Auth's message"
}

test_wrapper_shows_hmpps_auths_answer_in_laptop_mode() {
  local out; out="$(wrapper "$T/w5" TOKEN_SOURCE=laptop FAKE_SECRET=wrong)"
  assert_contains "$out" "HTTP 401 -- unauthorized: Bad credentials" "status and message"
}

test_wrapper_names_the_fix_for_a_missing_ui_deployment() {
  local out; out="$(wrapper "$T/w6" UI_DEPLOYMENT=nope)"
  assert_contains "$out" "set UI_NAMESPACE / UI_DEPLOYMENT" "hint"
}

# ---------------------------------------------------------------------------
echo "practitioner export scripts"
for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  run_test "$t"
done
echo
echo "$passed passed, $failed failed"
[[ $failed -eq 0 ]]
