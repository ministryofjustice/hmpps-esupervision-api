#!/usr/bin/env python3
"""
Build the NDelius handover CSV (ESUP-1956 follow-up).

Joins the note text produced by CheckinNoteExportTool back onto the contact list
NDelius supplied, preserving their row order and columns so the file we return
diffs cleanly against the one we were given.

Runs entirely offline - no database, no API. Inputs are files already on disk.

Usage:
    ./scripts/build_handover_csv.py delius_contacts.csv notes.jsonl \
        delius_note_corrections.csv

If notes.jsonl came from --full-note, three more columns (REVIEWED,
SUBMITTED_NOTE, REVIEWED_NOTE) are added automatically and NOTES holds the two
parts joined.

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

# CheckinNoteExportTool --full-note emits "reviewed"/"submittedNote"/"reviewedNote" as well.
# The extra columns only appear when the notes file actually has them, so the submission-only
# handover file keeps the exact shape NDelius has already seen.
FULL_NOTE_COLUMNS = ["REVIEWED", "SUBMITTED_NOTE", "REVIEWED_NOTE"]


def main(argv):
    if len(argv) != 4:
        sys.exit(__doc__)
    contacts_path, notes_path, out_path = argv[1:]

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

    full_note = any("reviewed" in row for row in notes.values())
    columns = OUT_COLUMNS + (FULL_NOTE_COLUMNS if full_note else [])
    if full_note:
        print("full-note mode: notes carry review parts, adding "
              f"{', '.join(FULL_NOTE_COLUMNS)}", file=sys.stderr)

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

        writer = csv.DictWriter(dst, fieldnames=columns,
                                quoting=csv.QUOTE_ALL, lineterminator="\r\n")
        writer.writeheader()

        for row in reader:
            cid = (row.get("CONTACT_ID") or "").strip()
            note = notes.pop(cid, None)
            if note is None:
                missing.append((row, "no note generated (unmatched, or no answers held)"))
                continue
            text = note.get("notes") or ""
            if "Check in answers:" not in text:
                missing.append((row, "generated note has no answers block"))
                continue
            if not text.split("Check in answers:", 1)[1].strip():
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
            if full_note:
                # NOTES is the two parts joined; these let NDelius take them separately
                # if they turn out to be able to amend just one.
                out_row["REVIEWED"] = str(note.get("reviewed", False)).lower()
                out_row["SUBMITTED_NOTE"] = note.get("submittedNote") or ""
                out_row["REVIEWED_NOTE"] = note.get("reviewedNote") or ""
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
