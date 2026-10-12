#!/usr/bin/env python3
"""
Build the NDelius handover CSV (ESUP-1956 follow-up).

Joins the questions-and-answers text produced by CheckinNoteExportTool back onto
the contact list NDelius supplied, preserving their row order and columns so the
file we return diffs cleanly against the one we were given.

NOTES holds only the "Check in answers:" block of the submitted check-in, which
is the part missing from the contact note NDelius holds. If notes.jsonl holds
whole notes (e.g. fetched by fetch_checkin_notes.sh), everything before that
heading is dropped.

Runs entirely offline - no database, no API. Inputs are files already on disk.

Usage:
    ./scripts/build_handover_csv.py delius_contacts.csv notes.jsonl \
        delius_note_corrections.csv [delius_crns.jsonl]

delius_crns.jsonl, written by run_delius_note_correction.sh, records whether
each CRN still exists in NDelius (the last line per CRN wins). When given,
contacts whose CRN is NOT_IN_DELIUS, or could not be checked, go to the
exceptions file instead of the handover.

Outputs:
    <output>.csv            one row per contact WITH a note, in their row order
    <output>.exceptions.csv contacts we produced no note for, with the reason

NOTE: the notes contain mental-health free text. Treat both outputs as sensitive
personal data, keep them outside the git repo, and delete working copies when done.
"""
import csv
import json
import sys

OUT_COLUMNS = [
    "CRN", "CONTACT_DATE", "START_TIME", "CONTACT_ID", "SOFT_DELETED",
    "CHECKIN_UUID", "SENSITIVE", "NOTES",
]

ANSWERS_HEADING = "Check in answers:"


def main(argv):
    if len(argv) not in (4, 5):
        sys.exit(__doc__)
    contacts_path, notes_path, out_path = argv[1:4]

    # latest NDelius check per CRN; None means no check was run at all
    crn_status = None
    if len(argv) == 5:
        crn_status = {}
        with open(argv[4], encoding="utf-8") as fh:
            for line in fh:
                if line.strip():
                    row = json.loads(line)
                    crn_status[row["crn"]] = row

    # note text keyed by contact id
    notes = {}
    with open(notes_path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            cid = str(row.get("contact_id", "")).strip()
            if not cid:
                sys.exit(f"ERROR: a line in {notes_path} has no contact_id")
            if cid in notes:
                sys.exit(f"ERROR: duplicate contact_id {cid} in {notes_path}")
            notes[cid] = row

    written, missing = 0, []
    exceptions_path = out_path.rsplit(".csv", 1)[0] + ".exceptions.csv"

    with open(contacts_path, newline="", encoding="utf-8-sig") as src, \
            open(out_path, "w", newline="", encoding="utf-8") as dst:
        reader = csv.DictReader(src)
        # be tolerant of header case/whitespace from their export
        reader.fieldnames = [f.strip().upper() for f in (reader.fieldnames or [])]
        for required in ("CRN", "CONTACT_ID", "CONTACT_DATE", "START_TIME"):
            if required not in reader.fieldnames:
                sys.exit(f"ERROR: {contacts_path} has no {required} column "
                         f"(found: {reader.fieldnames})")

        writer = csv.DictWriter(dst, fieldnames=OUT_COLUMNS,
                                quoting=csv.QUOTE_ALL, lineterminator="\r\n")
        writer.writeheader()

        for row in reader:
            cid = (row.get("CONTACT_ID") or "").strip()
            note = notes.pop(cid, None)
            if note is None:
                missing.append((row, "no note generated (unmatched, or no answers held)"))
                continue
            if crn_status is not None:
                crn = note.get("crn") or (row.get("CRN") or "").strip().upper()
                check = crn_status.get(crn)
                if check is None:
                    missing.append((row, "CRN was not checked against NDelius"))
                    continue
                if check.get("status") == "NOT_IN_DELIUS":
                    missing.append((row, "CRN no longer exists in NDelius"))
                    continue
                if check.get("status") != "FOUND":
                    missing.append((row, "could not confirm the CRN exists in NDelius"
                                         f" ({check.get('reason') or 'unknown'}) - re-run to retry"))
                    continue
            text = note.get("notes") or ""
            if ANSWERS_HEADING not in text:
                missing.append((row, "generated note has no answers block"))
                continue
            text = ANSWERS_HEADING + text.split(ANSWERS_HEADING, 1)[1].rstrip("\n")
            if not text[len(ANSWERS_HEADING):].strip():
                # heading with nothing under it: no survey key matched custom-labels,
                # so this note would tell NDelius nothing new
                missing.append((row, "answers block is empty"))
                continue
            out_row = {
                "CRN": (row.get("CRN") or "").strip(),
                "CONTACT_DATE": (row.get("CONTACT_DATE") or "").strip(),
                "START_TIME": (row.get("START_TIME") or "").strip(),
                "CONTACT_ID": cid,
                "SOFT_DELETED": (row.get("SOFT_DELETED") or "").strip(),
                "CHECKIN_UUID": note.get("checkinUuid") or "",
                # lower-case true/false rather than Python's True/False
                "SENSITIVE": str(note.get("sensitive")).lower(),
                "NOTES": text,
            }
            writer.writerow(out_row)
            written += 1

    if missing:
        with open(exceptions_path, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, quoting=csv.QUOTE_ALL, lineterminator="\r\n")
            w.writerow(["CRN", "CONTACT_DATE", "START_TIME", "CONTACT_ID", "REASON"])
            for row, reason in missing:
                w.writerow([
                    (row.get("CRN") or "").strip(),
                    (row.get("CONTACT_DATE") or "").strip(),
                    (row.get("START_TIME") or "").strip(),
                    (row.get("CONTACT_ID") or "").strip(),
                    reason,
                ])

    print(f"wrote {written} rows to {out_path}", file=sys.stderr)
    if missing:
        print(f"wrote {len(missing)} unresolved contacts to {exceptions_path}", file=sys.stderr)
    if notes:
        # notes with no matching contact row: should never happen
        print(f"WARNING: {len(notes)} generated notes had no matching contact row: "
              f"{sorted(notes)[:5]}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
