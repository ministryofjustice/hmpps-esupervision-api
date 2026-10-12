#!/usr/bin/env python3
"""
Offline halves of the Delius check-in note correction (ESUP-1956 follow-up).

scripts/delius_note_correction.sql runs as one read-only SELECT, with no temp
tables. This script does what the temp tables used to make possible on the
database side: it gets the contact list into the query, and it builds the
exception reports and exports from what the query returns.

Runs entirely offline: no database, no API.

Usage:
    delius_note_correction.py prepare delius_contacts.csv contacts.json
        Converts the CSV NDelius supplied into the JSON the SQL reads. Columns
        are matched by header name (case and surrounding spaces ignored), and
        row_no is the 1-based data row, so it lines up with their file.

    delius_note_correction.py report matched.jsonl out_dir
        Prints the match report to stdout -- read it before handover -- and
        writes, into out_dir:
          checkin_export.jsonl   feeds CheckinNoteExportTool
        Prints a one-line-per-section summary of anything needing attention
        to stderr.

NOTE: the files carry mental-health free text. Treat them as sensitive personal
data, keep them outside the git repo, and delete working copies when done.
"""
import csv
import json
import os
import sys
from collections import Counter, defaultdict

REQUIRED = ("CRN", "CONTACT_DATE", "START_TIME", "CONTACT_ID")
CUSTOM_QUESTIONS_VERSION = "2026-04-16@questions"


def prepare(csv_path, json_path):
    with open(csv_path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        reader.fieldnames = [f.strip().upper() for f in (reader.fieldnames or [])]
        for required in REQUIRED:
            if required not in reader.fieldnames:
                sys.exit(f"ERROR: {csv_path} has no {required} column (found: {reader.fieldnames})")
        # Blank fields become null, as \copy loaded them, so an empty START_TIME
        # or an all-commas padding row is reported as unparsed rather than
        # failing the query.
        def field(row, name):
            value = (row.get(name) or "").strip()
            return value or None

        contacts = [
            {
                "row_no": i,
                "crn": field(row, "CRN"),
                "contact_date": field(row, "CONTACT_DATE"),
                "start_time": field(row, "START_TIME"),
                "contact_id": field(row, "CONTACT_ID"),
                "soft_deleted": field(row, "SOFT_DELETED"),
            }
            for i, row in enumerate(reader, start=1)
        ]
    if not contacts:
        sys.exit(f"ERROR: {csv_path} has no data rows")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(contacts, fh)
    print(f"{len(contacts)} contacts -> {json_path}", file=sys.stderr)


def match_grade(row):
    if not row.get("checkinUuid"):
        return "UNMATCHED"
    delta = row["deltaSecs"]
    if delta <= 120:
        return "EXACT"
    if delta <= 3600:
        return "CLOSE"
    return "LOOSE"


def has_answers(row):
    return bool(row.get("surveyResponse"))


def table(title, headers, rows):
    print(f"\n-- {title}")
    if not rows:
        print("(none)")
        return
    cells = [[("" if v is None else str(v).lower() if isinstance(v, bool) else str(v)) for v in r] for r in rows]
    widths = [max(len(h), *(len(r[i]) for r in cells)) for i, h in enumerate(headers)]
    print(" | ".join(h.ljust(w) for h, w in zip(headers, widths)))
    print("-+-".join("-" * w for w in widths))
    for r in cells:
        print(" | ".join(v.ljust(w) for v, w in zip(r, widths)))
    print(f"({len(rows)} row{'' if len(rows) == 1 else 's'})")


def fmt_secs(v):
    return None if v is None else f"{float(v):.0f}"


def report(matched_path, out_dir):
    with open(matched_path, encoding="utf-8") as fh:
        rows = [json.loads(line) for line in fh if line.strip()]
    if not rows:
        sys.exit(f"ERROR: {matched_path} is empty")
    for r in rows:
        r["grade"] = match_grade(r)

    attention = {}

    # Step 1: the parse. Unparsed must be 0, and the latest contact should be
    # on or before the 2026-05-22 fix.
    parsed = [r["contactAt"] for r in rows if r.get("contactAt")]
    unparsed = [r for r in rows if not r.get("contactAt")]
    table("1. Contacts loaded", ["rows", "unparsed", "distinct_crns", "earliest_contact", "latest_contact", "soft_deleted_rows"], [[
        len(rows), len(unparsed), len({r["crn"] for r in rows}),
        min(parsed, default=None), max(parsed, default=None),
        sum(1 for r in rows if (r.get("softDeleted") or "0") != "0"),
    ]])
    table("1. Unparsed contact date/time", ["row_no", "crn", "contact_id", "contact_date", "start_time"],
          [[r["row_no"], r["crn"], r["contact_id"], r["contactDateRaw"], r["startTimeRaw"]] for r in unparsed])
    attention["unparsed contact date/time"] = len(unparsed)
    spellings = Counter(r.get("contactDateRaw") or "(blank)" for r in rows)
    table("1. Distinct raw date spellings (first 20)", ["contact_date", "count"], sorted(spellings.items())[:20])

    # 3a. Expect overwhelmingly EXACT; anything else needs eyes.
    by_grade = defaultdict(list)
    for r in rows:
        by_grade[r["grade"]].append(r)
    table("3a. Match grades", ["grade", "count", "min_delta_secs", "max_delta_secs"], [
        [g, len(rs),
         fmt_secs(min((r["deltaSecs"] for r in rs if r.get("deltaSecs") is not None), default=None)),
         fmt_secs(max((r["deltaSecs"] for r in rs if r.get("deltaSecs") is not None), default=None))]
        for g, rs in sorted(by_grade.items())
    ])
    not_exact = [r for r in rows if r["grade"] in ("CLOSE", "LOOSE")]
    table("3a. CLOSE/LOOSE matches", ["row_no", "crn", "contact_id", "contact_at", "submitted_at", "delta_secs", "grade"],
          [[r["row_no"], r["crn"], r["contact_id"], r.get("contactAt"), r.get("submittedAt"), fmt_secs(r["deltaSecs"]), r["grade"]]
           for r in not_exact])
    attention["CLOSE/LOOSE matches"] = len(not_exact)

    # 3b. CRNs we hold no check-in for (or no offender at all).
    unmatched = [r for r in rows if r["grade"] == "UNMATCHED"]
    table("3b. No check-in found", ["row_no", "crn", "contact_id", "contact_date", "start_time", "offender_found"],
          [[r["row_no"], r["crn"], r["contact_id"], r["contactDateRaw"], r["startTimeRaw"], r.get("offenderFound")]
           for r in unmatched])
    attention["no check-in found"] = len(unmatched)

    # 3c. Two contacts resolving to the same check-in: either a duplicate
    #     contact on their side, or one of the two matches is wrong.
    by_checkin = defaultdict(list)
    for r in rows:
        if r.get("checkinUuid"):
            by_checkin[r["checkinUuid"]].append(r["contact_id"])
    dupes = [[u, ", ".join(str(c) for c in cids)] for u, cids in by_checkin.items() if len(cids) > 1]
    table("3c. Check-in matched by more than one contact", ["checkin_uuid", "contact_ids"], dupes)
    attention["check-ins matched more than once"] = len(dupes)

    # 3d. More than one submission inside the window. We matched on nearest,
    #     so confirm the choice for these rows specifically.
    multi = [r for r in rows if len(r.get("candidateTimes") or []) > 1]
    table("3d. Several submissions in the +/- 1 day window", ["row_no", "crn", "contact_id", "contact_at", "chosen", "candidates"],
          [[r["row_no"], r["crn"], r["contact_id"], r.get("contactAt"), r.get("submittedAt"), ", ".join(r["candidateTimes"])]
           for r in multi])
    attention["several candidate submissions"] = len(multi)

    # 3e. Matched, but we have no answers to give. Not race-condition victims:
    #     they drop out of the handover, into the exceptions file.
    no_answers = [r for r in rows if r.get("checkinUuid") and not has_answers(r)]
    table("3e. Matched but no answers held", ["row_no", "crn", "contact_id", "checkin_uuid", "submitted_at", "status"],
          [[r["row_no"], r["crn"], r["contact_id"], r["checkinUuid"], r.get("submittedAt"), r.get("status")] for r in no_answers])
    attention["matched but no answers held"] = len(no_answers)

    # 3f. Custom questions the formatter would silently drop: it only renders
    #     them for version 2026-04-16@questions (v2/checkin/SurveyDetails.kt).
    dropped = []
    for r in rows:
        survey = r.get("surveyResponse") or {}
        custom = survey.get("customQuestions") or []
        if r.get("checkinUuid") and custom and survey.get("version") != CUSTOM_QUESTIONS_VERSION:
            dropped.append([r["row_no"], r["crn"], r["contact_id"], survey.get("version"), len(custom)])
    table("3f. Custom questions that would be dropped", ["row_no", "crn", "contact_id", "survey_version", "n_custom"], dropped)
    attention["custom questions that would be dropped"] = len(dropped)

    # 3g. The audit log's own occurred_at disagrees with the match by > 1 hour.
    audit = [r for r in rows if any(float(d) > 3600 for d in (r.get("auditDeltaSecs") or []))]
    table("3g. Audit log disagrees with the match (> 1 hour)", ["row_no", "crn", "contact_id", "checkin_uuid", "contact_at", "audit_delta_secs"],
          [[r["row_no"], r["crn"], r["contact_id"], r["checkinUuid"], r.get("contactAt"),
            ", ".join(fmt_secs(d) for d in r["auditDeltaSecs"])] for r in audit])
    attention["audit log disagrees"] = len(audit)

    # Exports: only check-ins with answers to give.
    export = [r for r in rows if r.get("checkinUuid") and has_answers(r)]
    with open(os.path.join(out_dir, "checkin_export.jsonl"), "w", encoding="utf-8") as fh:
        for r in export:
            fh.write(json.dumps({
                "row_no": r["row_no"],
                "contact_id": r["contact_id"],
                "crn": r["crn"],
                "checkinUuid": r["checkinUuid"],
                "submittedAt": r.get("submittedAt"),
                "sensitive": r.get("sensitive"),
                "surveyResponse": r["surveyResponse"],
            }, ensure_ascii=False) + "\n")

    print(f"\n{len(rows)} contacts, {len(export)} matched check-ins with answers to export")
    print(f"{len(rows)} contacts, {len(export)} with answers to export", file=sys.stderr)
    for what, n in attention.items():
        if n:
            print(f"  CHECK: {n} {what}", file=sys.stderr)


def main(argv):
    if len(argv) == 4 and argv[1] == "prepare":
        prepare(argv[2], argv[3])
    elif len(argv) == 4 and argv[1] == "report":
        report(argv[2], argv[3])
    else:
        sys.exit(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
