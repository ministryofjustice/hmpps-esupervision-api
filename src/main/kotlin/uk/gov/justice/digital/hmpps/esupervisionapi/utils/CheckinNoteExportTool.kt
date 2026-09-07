package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.fasterxml.jackson.databind.ObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.appendQuestionsAndAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One-off offline generator for NDelius check-in contact notes (ESUP-1956 follow-up).
 *
 * A race condition (fixed in 8eefb1a, 2026-05-22) published checkin-submitted events before
 * the transaction committed, so NDelius sometimes fetched the contact note before
 * survey_response was visible and stored it without the answers. NDelius supplied the list of
 * affected contacts; we owe them the correct note text per contact.
 *
 * Normally that text would come from GET /v2/events/checkin-submitted/{uuid}, but the roles on
 * that endpoint (ROLE_ESUPERVISION__CHECK_IN__RO / ROLE_ESUPERVISION__ESUPERVISION_UI) belong to
 * other clients. This tool produces the same text offline from a database export instead.
 *
 * It deliberately calls the PRODUCTION formatter [appendQuestionsAndAnswers] for the answers
 * block, so label ordering, value expansion, snake-case prettifying, blank-line placement and
 * custom-question rendering cannot drift from what the app would emit. Only the CHECKIN_SUBMITTED
 * preamble is reproduced here (see [formatSubmittedNote]); keep it in step with the
 * V2_CHECKIN_SUBMITTED branch of EventDetailService.formatCheckinNotes.
 *
 * Two modes:
 *  - default: the submission note only, for when NDelius can amend that part of the contact note.
 *  - --full-note: submission note + review note joined by NDelius's own separator rule (see
 *    NDELIUS_NOTE_SEPARATOR), for when NDelius has to replace the whole contact note. Reads the
 *    richer export from step 4c of the SQL script. Check-ins that were never reviewed get the
 *    submission part alone. See [formatReviewedNote]. Add --include-annotations to fold
 *    checkin-annotated entries in as further parts, ordered by when NDelius recorded them.
 *
 * Runs WITHOUT Spring and WITHOUT the test classpath (the JUnit LauncherSessionListener in
 * src/test/resources starts a Postgres testcontainer, which needs Docker):
 *
 *   ./gradlew assemble
 *   java -Dloader.main=uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinNoteExportToolKt \
 *     -cp build/libs/hmpps-esupervision-api-*.jar \
 *     org.springframework.boot.loader.launch.PropertiesLauncher \
 *     --hosted-at=https://esupervision.hmpps.service.justice.gov.uk \
 *     checkin_export.jsonl notes.jsonl
 *
 * Input: JSONL, one object per line, as exported by scripts/delius_note_correction.sql:
 *   {"row_no":1,"contact_id":"1996227159","crn":"E659305",
 *    "checkinUuid":"...","offenderUuid":"...","submittedAt":"2026-04-22T08:15:20.123Z",
 *    "autoIdCheck":"MATCH","livenessEnabled":true,"livenessResult":"LIVE","sensitive":false,
 *    "surveyResponse":{...}}
 * With --full-note, step 4c adds: "status", "reviewedAt", "manualIdCheck", "reviewAction",
 * "reviewEntryAt", "reviewEntryCount" and "annotations".
 *
 * Output: JSONL with the same identifiers plus "notes", and "warnings" where the note looks
 * suspect. --full-note additionally emits "reviewed", "submittedNote" and "reviewedNote" so the
 * parts can be handed over separately if NDelius wants them that way. Answer text is sensitive
 * personal data: keep the files outside the repo and delete working copies when done.
 */

private val JSON = ObjectMapper()

/**
 * The rule NDelius puts between the parts of a contact note when it appends to an existing one.
 * 57 hyphens, confirmed by the NDelius team. Only used to rebuild a whole note (--full-note);
 * the app itself never emits it, because each part was a separate domain event.
 */
private const val NDELIUS_NOTE_SEPARATOR = "---------------------------------------------------------"

/**
 * NDelius heads each part of a contact note with its own provenance line, e.g.
 * "Comment added by Online Check In Service on 31/03/2026 at 12:50". Like the separator this is
 * NDelius's rendering, not ours -- the app never emits it -- so it is reproduced here only to
 * rebuild a whole note. The timestamp is the moment NDelius recorded that part, which we
 * approximate with submitted_at / reviewed_at; see [ndeliusNoteHeader].
 */
private const val NDELIUS_NOTE_AUTHOR = "Online Check In Service"

/**
 * When the manual-ID-check copy changed (commit 5d51b911, PR #304, merged to main 2026-06-09
 * 13:16 BST). Before it, formatManualIdCheckResult said only "Yes" and "No"; after it, the
 * three-way "Yes, and there’s nothing concerning" / "Yes, and there’s visible concern" /
 * "No, it is not the person". A review is rendered with whichever copy was live when NDelius
 * fetched the note, so reviews from the affected window mostly need the OLD wording.
 *
 * This is the PROD DEPLOY time, from the "Deploy to prod" job of the pipeline run that first
 * carried the commit (run 27701874170) -- nine days after the merge, because the merge's own run
 * skipped prod. Override with --concern-copy-from=<ISO instant> if that turns out to be wrong;
 * the earliest reviewed_at carrying MATCH_WITH_CONCERN in prod is a hard upper bound.
 */
private const val CONCERN_COPY_FROM_DEFAULT = "2026-06-18T09:33:16Z"

/**
 * Prod deploy times for the changes that altered SUBMITTED note text between the start of the
 * affected window and now. Taken from each commit's "Deploy to prod" job in GitHub Actions, NOT
 * from the commit or merge date -- prod lagged the merge by hours or days in every case.
 *
 * A note was rendered by whatever code was live when NDelius fetched it, so these decide which
 * form to rebuild. Anything submitted before a boundary needs the older form.
 */
private val VIDEO_LINK_REMOVED_AT = Instant.parse("2026-04-24T15:45:04Z") // baecaf4e
private val LIVENESS_IN_RESULT_AT = Instant.parse("2026-04-28T08:07:58Z") // b0c41528
private val CONFIG_EXPANSIONS_AT = Instant.parse("2026-05-07T10:02:58Z") // e948cf35
private val OTHER_EXPANSION_AT = Instant.parse("2026-05-18T11:12:37Z") // e801800e
private val NDELIUS_HEADER_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy 'at' HH:mm", Locale.UK)
private val NDELIUS_ZONE = ZoneId.of("Europe/London")

fun main(args: Array<String>) {
  val flags = args.filter { it.startsWith("--") }
  val positional = args.filter { !it.startsWith("--") }

  val hostedAt = flags.firstOrNull { it.startsWith("--hosted-at=") }?.substringAfter("=")
    ?: System.getenv("HOSTED_AT")
    ?: fail(
      "hosted-at is required (prod: https://esupervision.hmpps.service.justice.gov.uk).\n" +
        "It builds the media proxy links exactly as AppConfig.mediaProxyUrl() does.",
    )
  // ESUP_1239 is true in dev/test/preprod/prod, so media links are included by default.
  val mediaLinks = !flags.contains("--no-media-links")
  val fullNote = flags.contains("--full-note")
  // How NDelius joins the submission and review parts on one contact is THEIR formatting, not
  // ours: the two were separate domain events, each fetched through its own detail URL. They
  // confirmed the separator is a line of 57 hyphens, so that is the default. Override with
  // --note-separator=... (\n in the value becomes a newline) if that turns out to be wrong,
  // e.g. --note-separator='\n\n---\n\n' to put blank lines around the rule.
  val separator = flags.firstOrNull { it.startsWith("--note-separator=") }
    ?.substringAfter("=")?.replace("\\n", "\n")
    ?: "\n$NDELIUS_NOTE_SEPARATOR\n"
  val noteAuthor = flags.firstOrNull { it.startsWith("--note-author=") }?.substringAfter("=")
    ?: NDELIUS_NOTE_AUTHOR
  // Only relevant when rebuilding a whole note: if NDelius re-stamps the parts itself, pass
  // --no-note-headers and hand over the bodies alone.
  val noteHeaders = !flags.contains("--no-note-headers")
  // Whose clock dates the submission half's header: ours (submitted_at, the default) or NDelius's
  // own contact date/time from the CSV they supplied. They should agree to the minute; where they
  // do not, a warning says so, and --header-time=contact takes theirs as authoritative.
  val headerFromContactTime = flags.contains("--header-time=contact")
  // Annotations were published as their own checkin-annotated events, so whether NDelius put them
  // on this contact or a new one is their behaviour, not ours. Confirmed for ESUP-1956 that they
  // land on the SAME contact, hence this flag; left opt-in so the default stays provable.
  val includeAnnotations = flags.contains("--include-annotations")
  val concernCopyFrom = (
    flags.firstOrNull { it.startsWith("--concern-copy-from=") }?.substringAfter("=")
      ?: CONCERN_COPY_FROM_DEFAULT
    ).let {
    runCatching { Instant.parse(it) }.getOrElse { _ -> fail("--concern-copy-from is not an ISO-8601 instant: $it") }
  }

  val inFile = File(positional.getOrNull(0) ?: fail("usage: <input.jsonl> [output.jsonl]"))
  val outFile = File(positional.getOrNull(1) ?: "notes.jsonl")
  if (!inFile.canRead()) fail("cannot read input file: $inFile")

  val surveyConfig = loadSurveyConfigFromApplicationYml()
  // e801800e added the OTHER expansion. Between CONFIG_EXPANSIONS_AT and OTHER_EXPANSION_AT the
  // config formatter was live but had no OTHER entry, so OTHER fell through to the snake-case
  // prettifier and rendered as "Other".
  val surveyConfigWithoutOther = SurveyValueExpansionsConfig(
    surveyConfig.expansions.filterKeys { it != "OTHER" },
    surveyConfig.customLabels,
  )
  System.err.println(
    "Loaded survey config: ${surveyConfig.customLabels.size} labels, " +
      "${surveyConfig.expansions.size} expansions",
  )

  var written = 0
  var suspect = 0
  var reviewedCount = 0
  var oldCopyCount = 0
  outFile.printWriter().use { out ->
    inFile.forEachLine { line ->
      if (line.isBlank()) return@forEachLine
      val row = JSON.readValue(line, Map::class.java)

      @Suppress("UNCHECKED_CAST")
      val survey = (row["surveyResponse"] as? Map<String, Any?>)
        ?.filterValues { it != null }
        ?.mapValues { it.value!! }

      val submittedInstant = (row["submittedAt"] as? String)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
      val submittedNote = formatSubmittedNote(
        survey = survey,
        submittedAt = submittedInstant,
        autoIdCheck = row["autoIdCheck"] as? String,
        livenessEnabled = row["livenessEnabled"] as? Boolean ?: false,
        livenessResult = row["livenessResult"] as? String,
        offenderUuid = row["offenderUuid"] as? String,
        checkinUuid = row["checkinUuid"] as? String,
        hostedAt = hostedAt,
        mediaLinks = mediaLinks,
        surveyConfig = surveyConfig,
        surveyConfigWithoutOther = surveyConfigWithoutOther,
      )

      // A check-in is reviewed if it reached the REVIEWED status. reviewed_at alone is not enough:
      // review_started_at/reviewed_by can be set on a review that was never completed, and only the
      // status transition (SUBMITTED -> REVIEWED, CheckinStatus.canTransitionTo) publishes the
      // checkin-reviewed event that produced the second half of the note.
      val reviewed = fullNote && row["status"] == "REVIEWED"
      val reviewedNote = if (reviewed) {
        formatReviewedNote(
          manualIdCheck = row["manualIdCheck"] as? String,
          reviewAction = row["reviewAction"] as? String,
          hasReviewEntry = ((row["reviewEntryCount"] as? Number)?.toInt() ?: 0) > 0,
          newConcernCopy = usesNewConcernCopy(row, concernCopyFrom),
        )
      } else {
        null
      }

      // Each part carries its own "Comment added by ..." line, dated when NDelius recorded it.
      // reviewed_at can be null on an older review, so fall back to the log entry's timestamp.
      val submittedAt = row["submittedAt"] as? String
      val contactAt = row["contactAt"] as? String
      val submittedPart = if (fullNote && noteHeaders) {
        val headerAt = if (headerFromContactTime) contactAt ?: submittedAt else submittedAt
        withNdeliusHeader(submittedNote, noteAuthor, headerAt)
      } else {
        submittedNote
      }
      val reviewedPart = reviewedNote?.let {
        if (noteHeaders) {
          withNdeliusHeader(it, noteAuthor, reviewedAt(row))
        } else {
          it
        }
      }

      @Suppress("UNCHECKED_CAST")
      val annotationParts = if (fullNote && includeAnnotations) {
        (row["annotations"] as? List<Map<String, Any?>> ?: emptyList()).map { annotation ->
          // V2_CHECKIN_ANNOTATED branch of formatCheckinNotes is just the comment text.
          val body = (annotation["notes"] as? String ?: "").trimEnd('\n')
          val at = annotation["createdAt"] as? String
          NotePart(at, if (noteHeaders) withNdeliusHeader(body, noteAuthor, at) else body)
        }
      } else {
        emptyList()
      }

      // The submission part is always first. The rest are ordered by when NDelius recorded them,
      // because an annotation can predate the review. Undated parts sort last -- they are warned
      // about separately, so they should not be silently interleaved on a guess.
      val tail = (listOfNotNull(reviewedPart?.let { NotePart(reviewedAt(row), it) }) + annotationParts)
        .sortedWith(compareBy(nullsLast()) { it.at?.let { at -> runCatching { Instant.parse(at) }.getOrNull() } })

      val notes = (listOf(submittedPart) + tail.map { it.text })
        .filter { it.isNotBlank() }
        .joinToString(separator)

      val warnings = warningsFor(notes, survey) +
        if (fullNote) {
          fullNoteWarnings(row, reviewed, noteHeaders && !headerFromContactTime, includeAnnotations, concernCopyFrom)
        } else {
          emptyList()
        }
      if (warnings.isNotEmpty()) suspect++

      val output = linkedMapOf<String, Any?>(
        "row_no" to row["row_no"],
        "contact_id" to row["contact_id"],
        "crn" to row["crn"],
        "checkinUuid" to row["checkinUuid"],
        "submittedAt" to row["submittedAt"],
        "sensitive" to row["sensitive"],
        "notes" to notes,
        "warnings" to warnings,
      )
      if (fullNote) {
        output["reviewed"] = reviewed
        output["submittedNote"] = submittedPart
        output["reviewedNote"] = reviewedPart
        output["annotationNotes"] = annotationParts.map { it.text }
      }
      out.println(JSON.writeValueAsString(output))
      written++
      if (reviewed) {
        reviewedCount++
        if (!usesNewConcernCopy(row, concernCopyFrom)) oldCopyCount++
      }
    }
  }
  System.err.println("wrote $written notes to $outFile ($suspect with warnings)")
  if (fullNote) {
    System.err.println("full-note mode: $reviewedCount of $written were reviewed, ${written - reviewedCount} submission-only")
    System.err.println(
      "manual ID check copy: $oldCopyCount reviewed before $concernCopyFrom use the pre-5d51b911 " +
        "wording (\"Yes\"/\"No\"), ${reviewedCount - oldCopyCount} use the current wording",
    )
  }
  if (suspect > 0) {
    System.err.println("Review the warnings before handing anything over:  jq -c 'select(.warnings|length>0)' $outFile")
  }
}

/**
 * Reproduces the V2_CHECKIN_SUBMITTED branch of EventDetailService.formatCheckinNotes.
 *
 * Kept line-for-line with that branch. The answers block is delegated to the production
 * formatter, and StringBuilder.appendLine/trimEnd are the same calls the service makes, so the
 * output is byte-identical for a given check-in.
 */
internal fun formatSubmittedNote(
  survey: Map<String, Any>?,
  submittedAt: Instant?,
  autoIdCheck: String?,
  livenessEnabled: Boolean,
  livenessResult: String?,
  offenderUuid: String?,
  checkinUuid: String?,
  hostedAt: String,
  mediaLinks: Boolean,
  surveyConfig: SurveyValueExpansionsConfig,
  surveyConfigWithoutOther: SurveyValueExpansionsConfig = surveyConfig,
): String {
  // An unknown submitted_at is treated as "current", and warned about separately.
  fun liveAt(boundary: Instant) = submittedAt == null || !submittedAt.isBefore(boundary)

  val sb = StringBuilder()
  sb.appendLine("Check in status: Submitted")
  sb.appendLine()
  autoIdCheck?.let {
    // baecaf4e made the label conditional on liveness; before it, it was always the short form.
    val label = if (liveAt(VIDEO_LINK_REMOVED_AT) && livenessEnabled) {
      "System ID and liveness check result"
    } else {
      "System ID check result"
    }
    val result = if (liveAt(LIVENESS_IN_RESULT_AT)) {
      val passed = if (livenessEnabled) {
        it == AutomatedIdVerificationResult.MATCH.name && livenessResult == LivenessResult.LIVE.name
      } else {
        it == AutomatedIdVerificationResult.MATCH.name
      }
      if (passed) "Pass" else "Fail"
    } else {
      // b0c41528 replaced formatAutoIdCheckResult, which ignored liveness entirely.
      when (it) {
        "MATCH" -> "Pass"
        "NO_MATCH", "NO_FACE_DETECTED", "ERROR" -> "Fail"
        else -> it
      }
    }
    sb.appendLine("$label: $result")
  }
  if (mediaLinks) {
    // ProxyLinkCreator + AppConfig.mediaProxyUrl(): "$hostedAt/resolve"
    val mediaProxy = "$hostedAt/resolve"
    sb.appendLine("Reference photo: $mediaProxy/offender/$offenderUuid/photo")
    sb.appendLine("Checkin snapshot: $mediaProxy/checkin/$checkinUuid/snapshot?index=0")
    if (!liveAt(VIDEO_LINK_REMOVED_AT)) {
      // baecaf4e removed this line (and ProxyLinkCreator.checkinVideo with it).
      sb.appendLine("Checkin video: $mediaProxy/checkin/$checkinUuid/video")
    }
  }
  survey?.let {
    sb.appendLine()
    when {
      !liveAt(CONFIG_EXPANSIONS_AT) -> sb.appendLegacyQuestionsAndAnswers(it)
      !liveAt(OTHER_EXPANSION_AT) -> sb.appendQuestionsAndAnswers(it, surveyConfigWithoutOther)
      else -> sb.appendQuestionsAndAnswers(it, surveyConfig)
    }
  }
  return sb.toString().trimEnd('\n')
}

/**
 * Reproduces the V2_CHECKIN_REVIEWED branch of EventDetailService.formatCheckinNotes.
 *
 * Kept line-for-line with that branch, including the two details that are easy to get wrong:
 *  - the second appendLine() is OUTSIDE the manualIdCheck let, so a check-in with no manual ID
 *    check gets two blank lines after the heading rather than one;
 *  - the action line is emitted whenever a review log entry EXISTS, even when its comment is
 *    empty. CheckinService coalesces a missing note to "" before saving, so a blank action line
 *    is what NDelius was actually sent, and dropping it here would not match.
 *
 * [reviewAction] must be the OLDEST OFFENDER_CHECKIN_REVIEW_SUBMITTED entry, which is what
 * findAllCheckinEvents (ordered desc) plus .lastOrNull() resolves to. Step 4c of the SQL selects
 * it that way.
 */
internal fun formatReviewedNote(
  manualIdCheck: String?,
  reviewAction: String?,
  hasReviewEntry: Boolean,
  newConcernCopy: Boolean = true,
): String {
  val sb = StringBuilder()
  sb.appendLine("Check in status: Reviewed")
  sb.appendLine()
  manualIdCheck?.let {
    sb.appendLine("Is the person in the video the correct person: ${formatManualIdCheckResult(it, newConcernCopy)}")
  }
  sb.appendLine()
  if (hasReviewEntry) {
    sb.appendLine("What action are you taking after reviewing this check in: ${reviewAction ?: ""}")
  }
  return sb.toString().trimEnd('\n')
}

/**
 * EventDetailService.formatManualIdCheckResult, in both of the wordings it has had.
 * The apostrophes are U+2019, as in the original. See [CONCERN_COPY_FROM_DEFAULT].
 */
private fun formatManualIdCheckResult(result: String, newCopy: Boolean): String = if (newCopy) {
  when (result) {
    "MATCH", "CONFIRMED" -> "Yes, and there\u2019s nothing concerning"
    "MATCH_WITH_CONCERN" -> "Yes, and there\u2019s visible concern"
    "NO_MATCH", "REJECTED" -> "No, it is not the person"
    else -> result
  }
} else {
  // Pre-5d51b911: MATCH_WITH_CONCERN did not exist, so it has no case here either.
  when (result) {
    "MATCH", "CONFIRMED" -> "Yes"
    "NO_MATCH", "REJECTED" -> "No"
    else -> result
  }
}

/** Whether this review was rendered with the post-5d51b911 copy, by when it was reviewed. */
private fun usesNewConcernCopy(row: Map<*, *>, cutover: Instant): Boolean {
  val at = reviewedAt(row)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    ?: return true // undated: assume current copy, and warn separately
  return !at.isBefore(cutover)
}

/** One part of a contact note, with the timestamp NDelius recorded it at (ISO-8601, may be null). */
private data class NotePart(val at: String?, val text: String)

/** reviewed_at, falling back to the review log entry's timestamp on older reviews. */
private fun reviewedAt(row: Map<*, *>): String? = (row["reviewedAt"] ?: row["reviewEntryAt"]) as? String

/**
 * Prefixes one part of a contact note with the provenance line NDelius writes above it, e.g.
 * "Comment added by Online Check In Service on 31/03/2026 at 12:50".
 *
 * [at] is an ISO-8601 instant from the export. It is our timestamp, not NDelius's: for the
 * submission half in particular the two can differ, because the race this whole exercise is about
 * meant the event was published fractionally BEFORE submitted_at was committed. Minute precision
 * absorbs that in all but a handful of cases -- diff a rebuilt note against what NDelius holds
 * before trusting it wholesale.
 *
 * Returns the part unchanged if there is no usable timestamp, rather than inventing one.
 */
private fun withNdeliusHeader(note: String, author: String, at: String?): String {
  val header = ndeliusNoteHeader(author, at) ?: return note
  return "$header\n$note"
}

private fun ndeliusNoteHeader(author: String, at: String?): String? {
  val stamp = ndeliusNoteHeaderTime(at) ?: return null
  return "Comment added by $author on $stamp"
}

/** The "DD/MM/YYYY at HH:MM" part alone, so two timestamps can be compared as NDelius renders them. */
private fun ndeliusNoteHeaderTime(at: String?): String? = at
  ?.let { runCatching { Instant.parse(it) }.getOrNull() }
  ?.atZone(NDELIUS_ZONE)
  ?.format(NDELIUS_HEADER_FORMATTER)

/** Warnings that only apply when reconstructing the whole contact note. */
/**
 * [compareHeaderTime] is false once --header-time=contact is in play: the header then uses NDelius's
 * own contact time, so a disagreement with our submitted_at has been resolved by that choice and is
 * no longer something to report.
 */
private fun fullNoteWarnings(
  row: Map<*, *>,
  reviewed: Boolean,
  compareHeaderTime: Boolean,
  includeAnnotations: Boolean,
  concernCopyFrom: Instant,
): List<String> {
  val warnings = mutableListOf<String>()
  val status = row["status"] as? String
  val reviewEntries = (row["reviewEntryCount"] as? Number)?.toInt() ?: 0

  // The header we write is dated from OUR submitted_at; NDelius dated the contact from the event
  // it received. To the minute they should be the same. Where they are not, the rebuilt header
  // will not match what NDelius holds, so say so rather than let it pass silently.
  if (compareHeaderTime) {
    val ours = ndeliusNoteHeaderTime(row["submittedAt"] as? String)
    val theirs = ndeliusNoteHeaderTime(row["contactAt"] as? String)
    if (ours != null && theirs != null && ours != theirs) {
      warnings += "header time disagrees: ours=$ours, NDelius contact=$theirs. Use --header-time=contact if theirs is right"
    }
  }

  if (row["submittedAt"] == null) {
    warnings += "no submittedAt: the \"Comment added by\" line is missing from the submission part, " +
      "and the note was rendered with the CURRENT formatter rather than the era it belongs to"
  }
  if (reviewed) {
    if (row["reviewedAt"] == null && row["reviewEntryAt"] == null) {
      warnings += "reviewed but no reviewedAt or review entry timestamp: the \"Comment added by\" line is missing from the review part"
    }
    if (row["manualIdCheck"] == null) {
      warnings += "reviewed but no manual ID check recorded: that line is absent from the note"
    }
    // MATCH_WITH_CONCERN did not exist before the copy change, so this combination is impossible
    // and means the cutover passed to --concern-copy-from is too late.
    if (row["manualIdCheck"] == "MATCH_WITH_CONCERN" && !usesNewConcernCopy(row, concernCopyFrom)) {
      warnings += "MATCH_WITH_CONCERN recorded before the copy cutover: --concern-copy-from is wrong, move it earlier"
    }
    if (reviewEntries == 0) {
      warnings += "status is REVIEWED but no review log entry: the action line is absent from the note"
    }
    if (reviewEntries > 1) {
      // findAllCheckinEvents orders desc and the service takes lastOrNull(), i.e. the oldest.
      warnings += "$reviewEntries review log entries: using the oldest, as the app does. Confirm by hand"
    }
  } else if (status != "SUBMITTED") {
    // CANCELLED, or a status this exercise did not anticipate. The submission part alone may be wrong.
    warnings += "status=$status is neither SUBMITTED nor REVIEWED: check what NDelius holds"
  }

  val annotations = row["annotations"] as? List<*>
  if (!annotations.isNullOrEmpty() && !includeAnnotations) {
    // Published as their own checkin-annotated events, so they may be separate NDelius notes.
    warnings += "has ${annotations.size} annotation(s), NOT included in this note: pass --include-annotations if NDelius put them on the same contact"
  }
  return warnings
}

private fun warningsFor(notes: String, survey: Map<String, Any>?): List<String> {
  val warnings = mutableListOf<String>()
  if (survey.isNullOrEmpty()) {
    warnings += "no survey response: nothing to correct"
    return warnings
  }
  if (!notes.contains("Check in answers:")) {
    warnings += "note has no answers block"
  } else if (notes.substringAfter("Check in answers:").isBlank()) {
    warnings += "answers block is empty: no survey key matched app.offender-survey.custom-labels"
  }
  // formatSurvey only renders customQuestions for survey version 2026-04-16@questions
  // (v2/checkin/SurveyDetails.kt). Anything else silently drops them.
  val custom = survey["customQuestions"] as? List<*>
  if (!custom.isNullOrEmpty() && survey["version"] != "2026-04-16@questions") {
    warnings += "has ${custom.size} customQuestions but version=${survey["version"]}: answers would be dropped"
  }
  return warnings
}

/**
 * Reads app.offender-survey.{expansions,custom-labels} from the application.yml shipped on the
 * classpath, so the labels and their ORDER (which determines the order of lines in the note) come
 * from the same source the running app uses. No environment overrides these two maps.
 *
 * Parsed as literal text rather than via a YAML library on purpose: YAML 1.1 resolves the
 * unquoted `YES:`/`NO:` expansion keys to booleans, which would corrupt the lookup table.
 */
internal fun loadSurveyConfigFromApplicationYml(): SurveyValueExpansionsConfig {
  val text = object {}.javaClass.classLoader.getResourceAsStream("application.yml")
    ?.bufferedReader()?.readText()
    ?: fail("application.yml not found on the classpath")

  val expansions = linkedMapOf<String, String>()
  val customLabels = linkedMapOf<String, String>()
  var section: MutableMap<String, String>? = null
  var inSurveyBlock = false

  for (raw in text.lines()) {
    if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
    val indent = raw.indexOfFirst { !it.isWhitespace() }
    val line = raw.trim()

    if (indent == 2 && line == "offender-survey:") {
      inSurveyBlock = true
      continue
    }
    if (!inSurveyBlock) continue
    // any key at the same or lower indent than `offender-survey:` ends the block
    if (indent <= 2) break

    when {
      indent == 4 && line == "expansions:" -> section = expansions
      indent == 4 && line == "custom-labels:" -> section = customLabels
      indent >= 6 && line.contains(':') -> {
        val key = line.substringBefore(':').trim()
        val value = line.substringAfter(':').trim().removeSurrounding("\"").removeSurrounding("'")
        (section ?: fail("value outside expansions/custom-labels in application.yml: $line"))[key] = value
      }
    }
  }

  if (expansions.isEmpty() || customLabels.isEmpty()) {
    fail("failed to parse app.offender-survey from application.yml (expansions=${expansions.size}, customLabels=${customLabels.size})")
  }
  return SurveyValueExpansionsConfig(expansions, customLabels)
}

private fun fail(message: String): Nothing {
  System.err.println("ERROR: $message")
  kotlin.system.exitProcess(1)
}
