#!/usr/bin/env bash
#
# Fetch NDelius contact note text for a list of check-ins (ESUP-1956 follow-up).
#
# Reads checkins_to_fetch.csv (produced by scripts/delius_note_correction.sql) and
# calls GET /v2/events/checkin-submitted/{uuid} for each row, capturing the `notes`
# field. That endpoint is the same code path NDelius used to fetch the note in the
# first place (EventDetailService.formatCheckinNotes), so the text is exact by
# construction — nothing is reimplemented here.
#
# READ ONLY: GETs only. The endpoint performs no writes.
#
# Auth: needs ROLE_ESUPERVISION__CHECK_IN__RO or ROLE_ESUPERVISION__ESUPERVISION_UI
# (see v2/checkin/EventResource.kt). Easiest source is a bearer token from a
# logged-in prod practitioner UI session: DevTools -> Network -> any /v2/ request
# -> copy the Authorization header value (without "Bearer ").
#
# Prod ingress is allowlisted to `internal`, so be on the MoJ network, or
# port-forward the service and set API_BASE=http://localhost:8080.
#
# Usage:
#   export TOKEN='eyJ...'
#   ./scripts/fetch_checkin_notes.sh [checkins_to_fetch.csv] [notes.jsonl]
#
# Env:
#   API_BASE    default https://esupervision-api.hmpps.service.justice.gov.uk
#   RATE_SLEEP  seconds between requests, default 0.5 (~2/s; ingress caps at
#               50 rps / 800 rpm, so this is comfortably under)
#
# Re-running is safe and resumes: check-ins already fetched successfully into the
# output file are skipped. If the token expires mid-run (401s), refresh TOKEN and
# run again — only the outstanding rows are retried.
#
# NOTE: the notes contain mental-health free text. Treat the output as sensitive
# personal data, keep it outside the git repo, and delete working copies when done.

set -euo pipefail

API_BASE="${API_BASE:-https://esupervision-api.hmpps.service.justice.gov.uk}"
IN="${1:-checkins_to_fetch.csv}"
OUT="${2:-notes.jsonl}"
RATE_SLEEP="${RATE_SLEEP:-0.5}"

[[ -n "${TOKEN:-}" ]] || { echo "ERROR: TOKEN is not set" >&2; exit 1; }
[[ -r "$IN" ]]        || { echo "ERROR: cannot read input file: $IN" >&2; exit 1; }
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

touch "$OUT"

# Resume support: UUIDs already fetched with a note.
already=$(jq -r 'select(.notes != null) | .checkinUuid' "$OUT" 2>/dev/null | sort -u || true)

total=0; ok=0; failed=0; skipped=0

while IFS=, read -r row_no contact_id crn uuid sensitive; do
  # skip header and blank lines
  [[ "$row_no" == "row_no" ]] && continue
  [[ -z "${uuid:-}" ]] && continue
  total=$((total + 1))

  if [[ -n "$already" ]] && grep -qxF "$uuid" <<<"$already"; then
    skipped=$((skipped + 1))
    continue
  fi

  body=$(mktemp)
  code=$(curl -s -o "$body" -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Accept: application/json' \
    "$API_BASE/v2/events/checkin-submitted/$uuid" || echo 000)

  if [[ "$code" == "200" ]]; then
    jq -c --arg cid "$contact_id" --arg row "$row_no" \
      '{row_no: ($row | tonumber), contact_id: $cid, checkinUuid, crn,
        sensitive, timestamp, eventReferenceId, notes}' "$body" >> "$OUT"
    ok=$((ok + 1))
  else
    jq -cn --arg cid "$contact_id" --arg row "$row_no" --arg u "$uuid" \
      --arg c "$code" --arg b "$(head -c 300 "$body" | tr -d '\r\n')" \
      '{row_no: ($row | tonumber), contact_id: $cid, checkinUuid: $u,
        error: ("HTTP " + $c), body: $b}' >> "$OUT"
    failed=$((failed + 1))
    echo "FAIL contact=$contact_id uuid=$uuid HTTP $code" >&2
    # A 401 means the token expired: stop rather than burn through the rest.
    if [[ "$code" == "401" ]]; then
      echo "ERROR: 401 — token expired or invalid. Refresh TOKEN and re-run (progress is kept)." >&2
      rm -f "$body"
      exit 1
    fi
  fi
  rm -f "$body"
  sleep "$RATE_SLEEP"
done < "$IN"

echo "rows=$total fetched=$ok failed=$failed already_present=$skipped" >&2

# ---------------------------------------------------------------------------
# Post-run checks (also worth running by hand before handover):
#
#   # any failures left?
#   jq -c 'select(.error)' notes.jsonl
#
#   # notes that lack an answers block, or stop at the heading with no answers
#   jq -r 'select(.notes) | select((.notes | contains("Check in answers:")) | not)
#          | .contact_id' notes.jsonl
#   jq -r 'select(.notes) | select(.notes | test("Check in answers:\\s*$"))
#          | .contact_id' notes.jsonl
#
#   # eyeball one in full
#   jq -r 'select(.notes) | .notes' notes.jsonl | head -30
# ---------------------------------------------------------------------------
