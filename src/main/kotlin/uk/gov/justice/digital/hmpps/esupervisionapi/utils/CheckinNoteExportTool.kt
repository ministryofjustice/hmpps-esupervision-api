package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.fasterxml.jackson.databind.ObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.config.SurveyValueExpansionsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.appendQuestionsAndAnswers
import java.io.File
import java.time.Instant

/**
 * One-off offline generator for the answers missing from NDelius check-in contact notes
 * (ESUP-1956 follow-up).
 *
 * A race condition (fixed in 8eefb1a, 2026-05-22) published checkin-submitted events before
 * the transaction committed, so NDelius sometimes fetched the contact note before
 * survey_response was visible and stored it without the answers. NDelius supplied the list of
 * affected contacts; we owe them the missing questions and answers per contact.
 *
 * Only the "Check in answers:" block of the submitted check-in is produced -- the part that is
 * absent from what NDelius holds. The rest of the contact note (status line, ID check result,
 * media links, review part) is already correct on their side and is not reproduced.
 *
 * It deliberately calls the PRODUCTION formatter [appendQuestionsAndAnswers], so label ordering,
 * value expansion, snake-case prettifying, blank-line placement and custom-question rendering
 * cannot drift from what the app would emit. Check-ins submitted before the formatter changed are
 * rendered with the wording that was live at the time (see [formatAnswers]).
 *
 * Runs WITHOUT Spring and WITHOUT the test classpath (the JUnit LauncherSessionListener in
 * src/test/resources starts a Postgres testcontainer, which needs Docker):
 *
 *   ./gradlew assemble
 *   java -Dloader.main=uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinNoteExportToolKt \
 *     -cp build/libs/hmpps-esupervision-api-*.jar \
 *     org.springframework.boot.loader.launch.PropertiesLauncher \
 *     checkin_export.jsonl notes.jsonl
 *
 * Input: JSONL, one object per line, as exported by scripts/delius_note_correction.sql:
 *   {"row_no":1,"contact_id":"1996227159","crn":"E659305","checkinUuid":"...",
 *    "submittedAt":"2026-04-22T08:15:20.123Z","sensitive":false,"surveyResponse":{...}}
 *
 * Output: JSONL with the same identifiers plus "notes" (the answers block), and "warnings" where
 * it looks suspect. Answer text is sensitive personal data: keep the files outside the repo and
 * delete working copies when done.
 */

private val JSON = ObjectMapper()

/**
 * Prod deploy times for the changes that altered the answers block during the affected window.
 * Taken from each commit's "Deploy to prod" job in GitHub Actions, NOT from the commit or merge
 * date -- prod lagged the merge by hours or days in every case. Anything submitted before a
 * boundary is rendered in the older form, because that is what NDelius would have been sent.
 */
internal val CONFIG_EXPANSIONS_AT: Instant = Instant.parse("2026-05-07T10:02:58Z") // e948cf35
internal val OTHER_EXPANSION_AT: Instant = Instant.parse("2026-05-18T11:12:37Z") // e801800e

fun main(args: Array<String>) {
  val positional = args.filter { !it.startsWith("--") }

  val inFile = File(positional.getOrNull(0) ?: fail("usage: <input.jsonl> [output.jsonl]"))
  val outFile = File(positional.getOrNull(1) ?: "notes.jsonl")
  if (!inFile.canRead()) fail("cannot read input file: $inFile")

  val surveyConfig = loadSurveyConfigFromApplicationYml()
  System.err.println(
    "Loaded survey config: ${surveyConfig.customLabels.size} labels, " +
      "${surveyConfig.expansions.size} expansions",
  )

  var written = 0
  var suspect = 0
  outFile.printWriter().use { out ->
    inFile.forEachLine { line ->
      if (line.isBlank()) return@forEachLine
      val row = JSON.readValue(line, Map::class.java)

      @Suppress("UNCHECKED_CAST")
      val survey = (row["surveyResponse"] as? Map<String, Any?>)
        ?.filterValues { it != null }
        ?.mapValues { it.value!! }

      val submittedAt = (row["submittedAt"] as? String)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
      val notes = survey?.let { formatAnswers(it, submittedAt, surveyConfig) } ?: ""

      val warnings = warningsFor(notes, survey) +
        if (submittedAt == null) listOf("no submittedAt: answers rendered with the CURRENT formatter rather than the era they belong to") else emptyList()
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
      out.println(JSON.writeValueAsString(output))
      written++
    }
  }
  System.err.println("wrote $written answer blocks to $outFile ($suspect with warnings)")
  if (suspect > 0) {
    System.err.println("Review the warnings before handing anything over:  jq -c 'select(.warnings|length>0)' $outFile")
  }
}

/**
 * The "Check in answers:" block of the V2_CHECKIN_SUBMITTED note, as
 * EventDetailService.formatCheckinNotes rendered it at [submittedAt]. An unknown submittedAt is
 * treated as "current", and warned about separately.
 */
internal fun formatAnswers(
  survey: Map<String, Any>,
  submittedAt: Instant?,
  surveyConfig: SurveyValueExpansionsConfig,
): String {
  fun liveAt(boundary: Instant) = submittedAt == null || !submittedAt.isBefore(boundary)

  val sb = StringBuilder()
  when {
    !liveAt(CONFIG_EXPANSIONS_AT) -> sb.appendLegacyQuestionsAndAnswers(survey)
    // e801800e added the OTHER expansion. Before it the config formatter was live but had no OTHER
    // entry, so OTHER fell through to the snake-case prettifier and rendered as "Other".
    !liveAt(OTHER_EXPANSION_AT) -> sb.appendQuestionsAndAnswers(
      survey,
      SurveyValueExpansionsConfig(surveyConfig.expansions.filterKeys { it != "OTHER" }, surveyConfig.customLabels),
    )
    else -> sb.appendQuestionsAndAnswers(survey, surveyConfig)
  }
  return sb.toString().trimEnd('\n')
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
