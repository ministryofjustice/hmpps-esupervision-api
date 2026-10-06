#!/usr/bin/env bash
#
# Tests for scripts/run_pop_contact_export.sh. Everything outside the script is
# faked: a python3 stub plays the API and HMPPS Auth, and fake kubectl and psql
# on PATH play Cloud Platform and the database.
#
# Usage:   ./scripts/test/pop_contact_export_test.sh
# Needs:   bash, jq, curl, python3, node (the pod token snippet runs on Node)
# Exit:    0 if every test passes, 1 if any fail, 2 if a prerequisite is
#          missing. PopContactExportScriptTest runs this from JUnit.

set -uo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/run_pop_contact_export.sh"

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

def person(crn, forename, mobile, status="VERIFIED", suspended=False, details=True):
  return {"uuid": "00000000-0000-0000-0000-000000000000", "crn": crn, "status": status,
          "firstCheckin": "2026-01-01", "mode": "SCHEDULED", "contactPreference": "PHONE",
          "details": None if not details else {
            "name": {"forename": forename, "surname": "SURNAME-" + crn},
            "dateOfBirth": "1990-01-01", "mobile": mobile, "email": crn.lower() + "@example.com",
            "practitioner": {"name": {"forename": "Pat", "surname": "Practitioner"}},
            "events": [], "contactSuspended": suspended}}

CASES = {
  "X000001": person("X000001", "Zoe", "07700900001"),
  "X000002": person("X000002", "  adam ", " 07700 900002 "),
  "X000003": person("X000003", "BARRY", "07700900003"),
  "X000004": person("X000004", "Cara", "07700900004", suspended=True),
  "X000005": person("X000005", "Dev", None),
  "X000006": person("X000006", "Eve", "   "),
  "X000007": person("X000007", "Finn", "07700900007", status="INACTIVE"),
  "X000008": person("X000008", "Gail", "07700900001"),   # shares Zoe's number
  "X000009": person("X000009", "Hana", "+44 (0)7700 900011"),
  "X000010": person("X000010", "Ian", "0044 7700 900012"),
  "X000011": person("X000011", "Jules", "+33 6 12 34 56 78"),
  "X000012": person("X000012", "Kim", "12345"),
  "X000013": person("X000013", "Lee", "020 7946 0958"),  # a landline
  "X000014": person("X000014", "Mo", "07700 90001"),      # a digit short
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
    m = re.match(r"/v2/offenders/crn/([^/?]+)\?include-personal-details=true$", self.path)
    if not m:
      return self.reply(400, {"error": "unexpected path " + self.path})
    crn = m.group(1)
    calls[crn] = calls.get(crn, 0) + 1
    if crn == "FORBID1":
      return self.reply(403, {})
    if crn == "EXPIRE1" and calls[crn] == 1:   # token expired mid-pass
      return self.reply(401, {})
    if crn == "EXPIRE1":
      return self.reply(200, person(crn, "Hal", "07700900009"))
    if crn == "FLAKY01" and calls[crn] == 1:   # NDelius blip, then fine
      return self.reply(200, person(crn, None, None, details=False))
    if crn == "FLAKY01":
      return self.reply(200, person(crn, "Ivy", "07700900010"))
    if crn == "DOWN001":                        # NDelius down throughout
      return self.reply(200, person(crn, None, None, details=False))
    if crn == "NOSTAT1":                        # 200, but not the shape we expect
      return self.reply(200, {"crn": crn})
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

printf '%s\n' X000001 X000002 X000003 X000004 X000005 X000006 X000007 X000008 \
  X000009 X000010 X000011 X000012 X000013 X000014 > "$T/crns.txt"

run() {  # workdir [extra env...]
  local dir="$1"; shift
  : > "$T/fake.log"
  env PATH="$T/bin:$PATH" FAKE_LOG="$T/fake.log" FAKE_CRNS="$T/crns.txt" FAKE_AUTH="$STUB/auth" \
      EXPORT_API_BASE="$STUB" EXPORT_AUTH_URL="$STUB/auth" RATE_SLEEP=0 "$@" \
      "$SCRIPT" "$dir" 2>&1
}

# Runs a command, discarding its output, and fails the test unless it exits
# with the given code -- so a run that should succeed cannot fail early and
# leave the assertions after it checking files that were never written.
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
test_exports_only_first_name_and_phone_for_contactable_people() {
  local out rc; out="$(run "$T/c1")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  # Names trimmed, as NDelius holds them otherwise, sorted case-insensitively.
  # UK numbers in any form normalised to +44; a non-UK one kept as held and
  # flagged. A shared number gets a row per person.
  assert_file_eq 'First name,Phone number,Non-UK number
"adam","+447700900002","No"
"BARRY","+447700900003","No"
"Gail","+447700900001","No"
"Hana","+447700900011","No"
"Ian","+447700900012","No"
"Jules","+33 6 12 34 56 78","Yes"
"Zoe","+447700900001","No"' "$T/c1/pop_contacts.csv" "export"
  assert_file_eq 'CRN,Reason
"X000004","contact_suspended"
"X000012","invalid_number"
"X000014","invalid_number"
"X000007","no_longer_active"
"X000005","no_mobile"
"X000006","no_mobile"
"X000013","not_a_mobile"' "$T/c1/excluded.csv" "excluded"
  [[ ! -e "$T/c1/pop_contacts.PARTIAL.csv" ]] || fail "complete run wrote a PARTIAL export"
  assert_contains "$out" "Exported:  7 of 14 active people" "summary"
  assert_contains "$out" "1 non-UK phone number, flagged" "non-UK numbers noted"
  assert_contains "$out" "1 phone number shared" "shared numbers noted"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_no_surname_email_dob_or_crn_in_the_export() {
  expect_exit 0 run "$T/c2"
  [[ -s "$T/c2/pop_contacts.csv" ]] || fail "no export to check"
  local csv; csv="$(cat "$T/c2/pop_contacts.csv")"
  for leak in SURNAME @example.com 1990-01-01 X0000 Practitioner 12345; do
    [[ "$csv" != *"$leak"* ]] || fail "export contains $leak"
  done
  [[ "$(cat "$T/c2/excluded.csv")" != *"07700"* ]] || fail "excluded.csv contains a phone number"
}

test_deletes_the_per_crn_working_file() {
  expect_exit 0 run "$T/c3"
  [[ ! -e "$T/c3/pop_contacts.jsonl" ]] || fail "pop_contacts.jsonl left behind"
  printf '%s\n' X000001 FORBID1 > "$T/fail-midway.txt"
  expect_exit 1 run "$T/c3b" FAKE_CRNS="$T/fail-midway.txt"
  [[ ! -e "$T/c3b/pop_contacts.jsonl" ]] || fail "pop_contacts.jsonl left behind after a failure"
  [[ -z "$(ls -A "$T/c3" "$T/c3b" | grep '^\.response\.')" ]] || fail "a response file was left behind"
}

test_selects_only_verified_crns_over_a_read_only_session() {
  expect_exit 0 run "$T/c4"
  assert_contains "$(cat "$T/fake.log")" "SELECT crn FROM offender_v2 WHERE status = 'VERIFIED'" "query"
  local all ro
  all=$(grep -c '^psql ' "$T/fake.log"); ro=$(grep -c '^psql PGOPTIONS=-c default_transaction_read_only=on ' "$T/fake.log")
  assert_eq "$all" "$ro" "every psql call read-only"
}

test_refuses_to_query_if_the_session_is_not_read_only() {
  local out rc; out="$(run "$T/c5" FAKE_READ_ONLY=off)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is not read-only" "message"
  [[ "$(cat "$T/fake.log")" != *"FROM offender_v2"* ]] || fail "ran the query anyway"
}

test_retries_an_ndelius_blip_on_the_next_pass() {
  printf '%s\n' X000001 FLAKY01 > "$T/flaky.txt"
  local out rc; out="$(run "$T/c6" FAKE_CRNS="$T/flaky.txt")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_contains "$out" "RETRY crn=FLAKY01 NDelius details unavailable" "retry logged"
  assert_file_eq 'First name,Phone number,Non-UK number
"Ivy","+447700900010","No"
"Zoe","+447700900001","No"' "$T/c6/pop_contacts.csv" "export after retry"
}

test_reports_crns_still_unresolved_and_exits_3() {
  printf '%s\n' X000001 DOWN001 ERROR01 NOSTAT1 NOSUCH1 > "$T/down.txt"
  local out rc; out="$(run "$T/c7" FAKE_CRNS="$T/down.txt")"; rc=$?
  assert_eq 3 "$rc" "exit code"
  assert_contains "$out" "4 of 5 CRNs are not in the export" "warning"
  assert_contains "$out" "RETRY crn=NOSTAT1 response has no status" "unexpected shape retried, not excluded"
  assert_contains "$out" "RETRY crn=ERROR01 HTTP 500" "server error retried"
  assert_contains "$out" "RETRY crn=NOSUCH1 CRN not registered" "404 retried"
  assert_file_eq $'DOWN001\nERROR01\nNOSTAT1\nNOSUCH1' "$T/c7/unresolved.txt" "unresolved list"
  assert_file_eq 'First name,Phone number,Non-UK number
"Zoe","+447700900001","No"' "$T/c7/pop_contacts.PARTIAL.csv" "resolved people exported, under a PARTIAL name"
  [[ ! -e "$T/c7/pop_contacts.csv" ]] || fail "partial run wrote pop_contacts.csv"
  assert_contains "$out" "named pop_contacts.PARTIAL.csv because it is incomplete" "partial named in the warning"
}

test_refreshes_the_token_on_a_401_mid_pass() {
  printf '%s\n' X000001 EXPIRE1 > "$T/expire.txt"
  local out rc; out="$(run "$T/c8" FAKE_CRNS="$T/expire.txt")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_eq 2 "$(grep -c '^kubectl .*exec deploy/hmpps-esupervision-ui' "$T/fake.log")" "token fetched for the pass and refreshed once"
  assert_file_eq 'First name,Phone number,Non-UK number
"Hal","+447700900009","No"
"Zoe","+447700900001","No"' "$T/c8/pop_contacts.csv" "export"
}

test_stops_on_a_403() {
  printf '%s\n' FORBID1 X000001 > "$T/forbid.txt"
  local out rc; out="$(run "$T/c9" FAKE_CRNS="$T/forbid.txt")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "missing ROLE_ESUPERVISION__ESUPERVISION_UI" "message"
  [[ ! -f "$T/c9/pop_contacts.csv" && ! -f "$T/c9/pop_contacts.PARTIAL.csv" ]] || fail "wrote an export anyway"
}

test_laptop_token_mode_works_and_reports_auth_errors() {
  local out rc; out="$(run "$T/c10" TOKEN_SOURCE=laptop)"; rc=$?
  assert_eq 0 "$rc" "laptop mode exit code"
  out="$(run "$T/c11" TOKEN_SOURCE=laptop FAKE_SECRET=wrong)"; rc=$?
  assert_eq 1 "$rc" "bad secret exit code"
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
  expect_exit 0 run "$T/c-tmp" TOKEN_SOURCE=laptop PATH="$T/mktemp-bin:$T/bin:$PATH"
  local outside
  outside=$(grep -v "^mktemp /.*/c-tmp/\.response\.XXXXXX$" "$T/mktemp.log")
  assert_eq "" "$outside" "temp files created outside the work dir"
}

test_stops_before_creating_a_pod_it_could_not_delete() {
  local out rc=0
  out="$(run "$T/nodelete" FAKE_DENY="delete pods")" || rc=$?
  [[ $rc -ne 0 ]] || fail "run succeeded without delete permission"
  assert_contains "$out" "cannot delete pods" "names the missing permission"
  ! grep -q "kubectl .* run " "$T/fake.log" || fail "a pod was created"
}

test_a_failure_while_writing_leaves_no_export_behind() {
  # jq fails on the export step alone, after the fetch has gone well
  mkdir -p "$T/jq-bin"
  cat > "$T/jq-bin/jq" <<STUB
#!/usr/bin/env bash
[[ "\$*" == *'"Yes" else "No"'* ]] && { echo "jq: disk full" >&2; exit 5; }
exec $(command -v jq) "\$@"
STUB
  chmod +x "$T/jq-bin/jq"
  local out rc=0
  out="$(run "$T/c-atomic" PATH="$T/jq-bin:$T/bin:$PATH")" || rc=$?
  [[ $rc -ne 0 ]] || fail "run succeeded although the export could not be written"
  assert_contains "$out" "jq: disk full" "the run reached the export step"
  [[ ! -e "$T/c-atomic/pop_contacts.csv" ]] || fail "a truncated pop_contacts.csv was left behind"
  [[ ! -e "$T/c-atomic/excluded.csv" ]] || fail "excluded.csv published without the export"
  [[ -z "$(ls -A "$T/c-atomic" | grep '\.tmp$')" ]] || fail "a temporary output was left behind"
}

test_a_failed_preflight_leaves_no_earlier_results_behind() {
  mkdir -p "$T/stale"
  echo "yesterday" > "$T/stale/pop_contacts.csv"
  local rc=0
  run "$T/stale" FAKE_DENY="create pods" >/dev/null || rc=$?
  [[ $rc -ne 0 ]] || fail "run succeeded without create permission"
  [[ ! -e "$T/stale/pop_contacts.csv" ]] || fail "an earlier run's pop_contacts.csv was left in place"
}

test_the_database_password_is_not_passed_on_after_the_sql_step() {
  local rc=0
  run "$T/pgenv" >/dev/null || rc=$?
  assert_eq 0 "$rc" "exit code"
  grep -q "^PGPASSWORD seen by kubectl .*port-forward" "$T/fake.log" || fail "fake did not detect PGPASSWORD at all"
  local late
  late=$(grep "^PGPASSWORD seen by kubectl .* \(exec\|delete\) " "$T/fake.log")
  assert_eq "" "$late" "PGPASSWORD still set for later commands"
}

test_pod_names_are_unique_per_run() {
  local first second
  expect_exit 0 run "$T/c-pod1"; first=$(awk '$4 == "run" {print $5}' "$T/fake.log")
  expect_exit 0 run "$T/c-pod2"; second=$(awk '$4 == "run" {print $5}' "$T/fake.log")
  # The PID alone can repeat across machines: the name needs a random part.
  [[ "$first" =~ ^pop-contact-export-[a-z0-9]+-[0-9]+-[0-9a-f]{8}$ ]] || fail "pod name has no random suffix: $first"
  [[ "$first" != "$second" ]] || fail "pod names not unique: '$first' then '$second'"
  (( ${#first} <= 63 )) || fail "pod name longer than 63 characters: $first"
}

test_pod_token_mode_does_not_read_the_ui_secret() {
  expect_exit 0 run "$T/c12"
  assert_contains "$(cat "$T/fake.log")" "exec deploy/hmpps-esupervision-ui" "token from the pod"
  [[ "$(cat "$T/fake.log")" != *"ui-client-creds"* ]] || fail "pod mode read the UI client secret"
}

test_rerun_starts_afresh() {
  printf '%s\n' X000001 DOWN001 > "$T/rerun.txt"
  expect_exit 3 run "$T/c13" FAKE_CRNS="$T/rerun.txt"
  [[ -f "$T/c13/unresolved.txt" && -f "$T/c13/pop_contacts.PARTIAL.csv" ]] || fail "first run should be partial"
  expect_exit 0 run "$T/c13"
  [[ ! -f "$T/c13/unresolved.txt" ]] || fail "stale unresolved.txt kept"
  [[ ! -f "$T/c13/pop_contacts.PARTIAL.csv" ]] || fail "stale PARTIAL export kept beside the complete one"
  assert_eq 8 "$(wc -l < "$T/c13/pop_contacts.csv" | tr -d ' ')" "header plus seven people"
}

test_work_dir_and_files_are_private() {
  expect_exit 0 run_umask_022 "$T/c14"
  assert_eq "drwx------" "$(ls -ld "$T/c14" | cut -c1-10)" "work dir permissions"
  local f
  for f in pop_contacts.csv excluded.csv active_crns.txt; do
    assert_eq "-rw-------" "$(ls -l "$T/c14/$f" | cut -c1-10)" "$f permissions"
  done
}

test_refuses_a_work_dir_inside_the_repo() {
  local out rc; out="$(run "$REPO_ROOT/build/pop-contact-export-test")"; rc=$?
  rmdir "$REPO_ROOT/build/pop-contact-export-test" 2>/dev/null
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is inside the repo" "message"
}

test_deletes_the_pod_when_the_sql_step_fails() {
  local out rc; out="$(run "$T/c15" FAKE_PSQL_FAIL=1)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "SQL step failed" "message"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_rejects_a_bad_passes_value() {
  local out rc; out="$(run "$T/c16" PASSES=0)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "PASSES must be a positive whole number" "message"
}

# ---------------------------------------------------------------------------
echo "pop contact export script"
for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  run_test "$t"
done
echo
echo "$passed passed, $failed failed"
[[ $failed -eq 0 ]]
