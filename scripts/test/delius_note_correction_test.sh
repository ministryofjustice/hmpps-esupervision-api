#!/usr/bin/env bash
#
# Tests for scripts/run_delius_note_correction.sh, chiefly the NDelius CRN
# check that decides whether a contact goes into the handover or is held back.
# Everything outside the scripts is faked: a python3 stub plays the API and
# HMPPS Auth, fake kubectl, psql and java on PATH play Cloud Platform, the
# database and CheckinNoteExportTool. The real report and handover steps run.
#
# Usage:   ./scripts/test/delius_note_correction_test.sh
# Needs:   bash, jq, curl, python3, node (the pod token snippet runs on Node)
# Exit:    0 if every test passes, 1 if any fail, 2 if a prerequisite is
#          missing. DeliusNoteCorrectionScriptTest runs this from JUnit.

set -uo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

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
# A copy of the repo's scripts, with a stand-in jar, so SKIP_BUILD=1 finds a
# jar without depending on (or touching) the real build output.
# ---------------------------------------------------------------------------
REPO="$T/repo"
mkdir -p "$REPO/scripts" "$REPO/build/libs"
cp "$REPO_ROOT"/scripts/{run_delius_note_correction.sh,delius_note_correction.py,delius_note_correction.sql,build_handover_csv.py} "$REPO/scripts/"
: > "$REPO/build/libs/hmpps-esupervision-api-test.jar"
SCRIPT="$REPO/scripts/run_delius_note_correction.sh"

# ---------------------------------------------------------------------------
# Fakes
# ---------------------------------------------------------------------------
cat > "$T/stub.py" <<'EOF'
import base64, json, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def header(errors=()):
  return {"crn": "x", "errors": [{"field": f, "code": c} for f, c in errors]}

GOOD_BASIC = "Basic " + base64.b64encode(b"ui-client:s3cr3t").decode()
calls = {}

class Handler(BaseHTTPRequestHandler):
  def reply(self, status, body):
    data = body if isinstance(body, bytes) else json.dumps(body).encode()
    self.send_response(status)
    self.send_header("Content-Type", "application/json")
    self.send_header("Content-Length", str(len(data)))
    self.end_headers()
    self.wfile.write(data)

  def do_POST(self):
    if self.path.startswith("/auth/oauth/token") and self.headers.get("Authorization") == GOOD_BASIC:
      return self.reply(200, {"access_token": "tok"})
    self.reply(401, {"error": "unauthorized"})

  def do_GET(self):
    if self.headers.get("Authorization") != "Bearer tok":
      return self.reply(401, {})
    m = re.match(r"/v2/offenders/header/([^/]+)$", self.path)
    crn = m.group(1) if m else None
    calls[crn] = calls.get(crn, 0) + 1
    if crn == "GONE001":                        # NDelius does not know the CRN
      return self.reply(404, {})
    if crn == "FORBID1":
      return self.reply(403, {})
    if crn == "EXPIRE1" and calls[crn] == 1:   # token expired mid-pass
      return self.reply(401, {})
    if crn == "DOWN001" or (crn == "FLAKY01" and calls[crn] == 1):
      # NDelius could not be asked: still a 200, failure named against dateOfBirth
      return self.reply(200, header([("dateOfBirth", "SERVICE_UNAVAILABLE")]))
    if crn == "BROKEN1":
      return self.reply(200, b"<html>not json")
    if crn == "ERR5001":
      return self.reply(500, {})
    if crn == "OTHERER":                        # an error elsewhere does not matter
      return self.reply(200, header([("tierScore", "SERVICE_UNAVAILABLE")]))
    self.reply(200, header())

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
  *"get deploy/"*) exit 0 ;;
  *" exec "*)
    HMPPS_AUTH_URL="$FAKE_AUTH" CLIENT_CREDS_CLIENT_ID=ui-client CLIENT_CREDS_CLIENT_SECRET=s3cr3t \
      node -e "${@: -1}" ;;
  *"get secret hmpps-esupervision-rds-settings"*)
    printf '{"data":{"rds_instance_endpoint":"%s","database_username":"%s","database_name":"%s","database_password":"%s"}}' \
      "$(b64 rds.example:5432)" "$(b64 dbuser)" "$(b64 dbname)" "$(b64 dbpass)" ;;
  *" run "*|*" wait "*|*" delete "*) exit 0 ;;
  *"port-forward"*) exec sleep 300 ;;
  *) echo "unexpected kubectl call: $*" >&2; exit 1 ;;
esac
EOF
# The SQL step: one matched check-in, with answers, per contact in contacts.json.
cat > "$T/bin/psql" <<'EOF'
#!/usr/bin/env bash
echo "psql PGOPTIONS=${PGOPTIONS:-} args=$*" >> "$FAKE_LOG"
case "$*" in
  *"select 1"*) echo 1 ;;
  *"show default_transaction_read_only"*)
    if [[ "${PGOPTIONS:-}" == *"default_transaction_read_only=on"* && "${FAKE_READ_ONLY:-}" != off ]]
    then echo on; else echo off; fi ;;
  *"-f "*delius_note_correction.sql*)
    python3 - <<'PY'
import json
with open("contacts.json") as fh, open("matched.jsonl", "w") as out:
  for c in json.load(fh):
    out.write(json.dumps({
      "row_no": c["row_no"], "crn": c["crn"], "contact_id": c["contact_id"],
      "contactDateRaw": c["contact_date"], "startTimeRaw": c["start_time"],
      "softDeleted": c["soft_deleted"], "contactAt": "2026-04-22T09:15:20.000Z",
      "offenderFound": True, "checkinUuid": "uuid-" + c["crn"],
      "submittedAt": "2026-04-22T09:15:21.000Z", "status": "SUBMITTED", "sensitive": False,
      "deltaSecs": 1, "candidateTimes": ["2026-04-22T09:15:21.000Z"], "auditDeltaSecs": [1],
      "surveyResponse": {"version": "2025-07-10@pilot", "mentalHealth": "WELL"},
    }) + "\n")
PY
    ;;
  *) echo "unexpected psql call: $*" >&2; exit 1 ;;
esac
EOF
# CheckinNoteExportTool: <export.jsonl> <notes.jsonl> are the last two arguments.
cat > "$T/bin/java" <<'EOF'
#!/usr/bin/env bash
python3 - "${@: -2}" <<'PY'
import json, sys
with open(sys.argv[1]) as src, open(sys.argv[2], "w") as out:
  for line in src:
    r = json.loads(line)
    out.write(json.dumps({k: r.get(k) for k in ("row_no", "contact_id", "crn", "checkinUuid", "submittedAt", "sensitive")}
                         | {"notes": "Check in answers:\nHow they have been feeling: Well", "warnings": []}) + "\n")
PY
EOF
chmod +x "$T/bin/kubectl" "$T/bin/psql" "$T/bin/java"

contacts() {  # file crn...
  local f="$1" i=0; shift
  echo "CRN,CONTACT_DATE,START_TIME,CONTACT_ID,SOFT_DELETED" > "$f"
  for crn in "$@"; do i=$((i + 1)); echo "$crn,22/04/2026,10:15:20,10$i,0" >> "$f"; done
}

run() {  # contacts.csv workdir [extra env...]
  local csv="$1" dir="$2"; shift 2
  : > "$T/fake.log"
  env PATH="$T/bin:$PATH" FAKE_LOG="$T/fake.log" FAKE_AUTH="$STUB/auth" \
      EXPORT_API_BASE="$STUB" SKIP_BUILD=1 RATE_SLEEP=0 "$@" \
      "$SCRIPT" "$csv" "$dir" 2>&1
}

handover_crns() { python3 -c 'import csv,sys; print(" ".join(r["CRN"] for r in csv.DictReader(open(sys.argv[1]))))' "$1" 2>/dev/null; }
exception() {  # file crn -> reason
  python3 -c 'import csv,sys; print(next((r["REASON"] for r in csv.DictReader(open(sys.argv[1])) if r["CRN"] == sys.argv[2]), ""))' "$1" "$2" 2>/dev/null
}

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
test_a_crn_ndelius_knows_goes_into_the_handover() {
  contacts "$T/found.csv" FOUND01 OTHERER
  local out rc; out="$(run "$T/found.csv" "$T/w-found")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_eq "FOUND01 OTHERER" "$(handover_crns "$T/w-found/delius_note_corrections.csv")" "handover rows"
  [[ ! -e "$T/w-found/delius_note_corrections.exceptions.csv" ]] || fail "wrote an exceptions file"
  assert_contains "$(cat "$T/fake.log")" "delete pod" "port-forward pod deleted"
}

test_a_404_is_held_back_as_no_longer_in_ndelius() {
  contacts "$T/gone.csv" FOUND01 GONE001
  local out rc; out="$(run "$T/gone.csv" "$T/w-gone")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_eq "FOUND01" "$(handover_crns "$T/w-gone/delius_note_corrections.csv")" "handover rows"
  assert_eq "CRN no longer exists in NDelius" "$(exception "$T/w-gone/delius_note_corrections.exceptions.csv" GONE001)" "exception reason"
  assert_eq 1 "$(jq -s 'map(select(.crn == "GONE001")) | length' "$T/w-gone/delius_crns.jsonl")" "a 404 is final, not retried"
}

test_ndelius_unavailable_on_every_pass_is_held_back_and_exits_3() {
  contacts "$T/down.csv" FOUND01 DOWN001
  local out rc; out="$(run "$T/down.csv" "$T/w-down")"; rc=$?
  assert_eq 3 "$rc" "exit code"
  assert_contains "$out" "RETRY crn=DOWN001 NDelius SERVICE_UNAVAILABLE" "retry logged"
  assert_eq "FOUND01" "$(handover_crns "$T/w-down/delius_note_corrections.csv")" "handover rows"
  assert_contains "$(exception "$T/w-down/delius_note_corrections.exceptions.csv" DOWN001)" \
    "could not confirm the CRN exists in NDelius (NDelius SERVICE_UNAVAILABLE)" "exception reason"
  assert_eq 2 "$(jq -s 'map(select(.crn == "DOWN001")) | length' "$T/w-down/delius_crns.jsonl")" "tried on both passes"
}

test_ndelius_unavailable_then_found_on_the_next_pass() {
  contacts "$T/flaky.csv" FLAKY01
  local out rc; out="$(run "$T/flaky.csv" "$T/w-flaky")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_contains "$out" "RETRY crn=FLAKY01" "retry logged"
  assert_eq "FLAKY01" "$(handover_crns "$T/w-flaky/delius_note_corrections.csv")" "handover rows"
}

test_an_unreadable_200_or_a_5xx_is_unchecked_not_found() {
  contacts "$T/bad.csv" BROKEN1 ERR5001
  local out rc; out="$(run "$T/bad.csv" "$T/w-bad" PASSES=1)"; rc=$?
  assert_eq 3 "$rc" "exit code"
  assert_eq "" "$(handover_crns "$T/w-bad/delius_note_corrections.csv")" "handover rows"
  assert_contains "$(exception "$T/w-bad/delius_note_corrections.exceptions.csv" BROKEN1)" "unreadable response" "BROKEN1 reason"
  assert_contains "$(exception "$T/w-bad/delius_note_corrections.exceptions.csv" ERR5001)" "HTTP 500" "ERR5001 reason"
}

test_a_401_mid_pass_refreshes_the_token_once() {
  contacts "$T/expire.csv" FOUND01 EXPIRE1
  local out rc; out="$(run "$T/expire.csv" "$T/w-expire")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_eq 2 "$(grep -c '^kubectl .*exec deploy/hmpps-esupervision-ui' "$T/fake.log")" "token fetched for the pass and refreshed once"
  assert_eq "FOUND01 EXPIRE1" "$(handover_crns "$T/w-expire/delius_note_corrections.csv")" "handover rows"
}

test_a_403_stops_before_anything_is_handed_over() {
  contacts "$T/forbid.csv" FOUND01 FORBID1
  local out rc; out="$(run "$T/forbid.csv" "$T/w-forbid")"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "missing ROLE_ESUPERVISION__ESUPERVISION_UI" "message"
  [[ ! -e "$T/w-forbid/delius_note_corrections.csv" ]] || fail "wrote a handover anyway"
}

test_refuses_to_query_if_the_session_is_not_read_only() {
  contacts "$T/ro.csv" FOUND01
  local out rc; out="$(run "$T/ro.csv" "$T/w-ro" FAKE_READ_ONLY=off)"; rc=$?
  assert_eq 1 "$rc" "exit code"
  assert_contains "$out" "is not read-only" "message"
  [[ "$(cat "$T/fake.log")" != *"delius_note_correction.sql"* ]] || fail "ran the SQL anyway"
}

test_an_input_already_in_the_work_dir_survives_the_clean_up() {
  mkdir -p "$T/w-same"
  contacts "$T/w-same/delius_contacts.csv" FOUND01
  local before; before="$(cat "$T/w-same/delius_contacts.csv")"
  local out rc; out="$(run "$T/w-same/delius_contacts.csv" "$T/w-same")"; rc=$?
  assert_eq 0 "$rc" "exit code"
  assert_eq "$before" "$(cat "$T/w-same/delius_contacts.csv" 2>/dev/null)" "input preserved"
  assert_eq "FOUND01" "$(handover_crns "$T/w-same/delius_note_corrections.csv")" "handover rows"
  assert_eq "" "$(ls -A "$T/w-same" | grep '^\.delius_contacts\.')" "staging file left behind"
}

# ---------------------------------------------------------------------------
echo "delius note correction script"
for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  run_test "$t"
done
echo
echo "$passed passed, $failed failed"
[[ $failed -eq 0 ]]
