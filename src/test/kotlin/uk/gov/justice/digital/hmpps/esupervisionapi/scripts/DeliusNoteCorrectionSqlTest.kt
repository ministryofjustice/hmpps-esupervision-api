package uk.gov.justice.digital.hmpps.esupervisionapi.scripts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.Container
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.TestContainersSessionListener
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs scripts/delius_note_correction.sql for real: with psql, inside the test Postgres container,
 * against the schema Liquibase has fully migrated, on a contacts.json made by the real
 * `delius_note_correction.py prepare` step -- as run_delius_note_correction.sh does against prod.
 *
 * Like the run script, every psql session is opened with default_transaction_read_only=on.
 */
class DeliusNoteCorrectionSqlTest : IntegrationTestBase() {

  @Autowired lateinit var offenderRepository: OffenderRepository

  @Autowired lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  private val mapper = jacksonObjectMapper()

  @AfterEach
  fun cleanUp() {
    checkinRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  @Test
  fun `matches a DD-MM-YYYY London-time contact to the check-in submitted seconds away`() {
    val checkin = submittedCheckin(offender("Z950001"), "2026-04-22T09:15:18Z", mapOf("mentalHealth" to "FEELING_GREAT"))

    val row = runScript("Z950001,22/04/2026,10:15:20,1001,0").single()

    assertThat(row.text("crn")).isEqualTo("Z950001")
    assertThat(row.text("contact_id")).isEqualTo("1001")
    assertThat(row.text("contactAt")).isEqualTo("2026-04-22T09:15:20.000Z")
    assertThat(row.get("offenderFound").asBoolean()).isTrue()
    assertThat(row.text("checkinUuid")).isEqualTo(checkin.uuid.toString())
    assertThat(row.text("submittedAt")).isEqualTo("2026-04-22T09:15:18.000Z")
    assertThat(row.get("deltaSecs").asDouble()).isEqualTo(2.0)
    assertThat(row.get("surveyResponse").get("mentalHealth").asString()).isEqualTo("FEELING_GREAT")
  }

  @Test
  fun `accepts YYYY-MM-DD contact dates too`() {
    val checkin = submittedCheckin(offender("Z950001"), "2026-04-22T09:15:20Z", mapOf("mentalHealth" to "OK"))

    val row = runScript("Z950001,2026-04-22,10:15:20,1001,0").single()

    assertThat(row.text("contactAt")).isEqualTo("2026-04-22T09:15:20.000Z")
    assertThat(row.text("checkinUuid")).isEqualTo(checkin.uuid.toString())
  }

  @Test
  fun `leaves malformed or impossible dates and times unparsed without aborting the run`() {
    val checkin = submittedCheckin(offender("Z950001"), "2026-04-22T09:15:20Z", mapOf("mentalHealth" to "OK"))
    offender("Z950002")

    val rows = runScript(
      "Z950002,31/02/2026,10:15:20,2001,0",
      "Z950002,22/04/2026,25:00,2002,0",
      "Z950001,22/04/2026,10:15:20,1001,0",
      "Z950002,13/13/2026,10:15:20,2003,0",
      "Z950001,22/04/2026,,2004,0",
    )

    assertThat(rows.map { it.get("row_no").asInt() }).containsExactly(1, 2, 3, 4, 5)
    val (bad, good) = rows.partition { it.text("contact_id") != "1001" }
    bad.forEach { row ->
      assertThat(row.get("contactAt").isNull).withFailMessage { "contactAt parsed for $row" }.isTrue()
      assertThat(row.get("checkinUuid").isNull).withFailMessage { "matched $row" }.isTrue()
      assertThat(row.get("offenderFound").asBoolean()).isTrue()
    }
    assertThat(bad.map { it.text("contactDateRaw") }).containsExactly("31/02/2026", "22/04/2026", "13/13/2026", "22/04/2026")
    assertThat(good.single().text("checkinUuid")).isEqualTo(checkin.uuid.toString())
  }

  @Test
  fun `does not change the database`() {
    submittedCheckin(offender("Z950001"), "2026-04-22T09:15:20Z", mapOf("mentalHealth" to "OK"))
    val before = tableCounts()

    runScript("Z950001,22/04/2026,10:15:20,1001,0")

    assertThat(tableCounts()).isEqualTo(before)
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Writes the CSV rows, runs the real prepare step on them, then runs the SQL with psql in the
   * container from a directory holding contacts.json, and returns matched.jsonl.
   */
  private fun runScript(vararg csvRows: String): List<JsonNode> {
    val hostDir = Files.createTempDirectory("delius-note-correction").toFile()
    try {
      val csv = File(hostDir, "contacts.csv").apply {
        writeText((listOf("CRN,CONTACT_DATE,START_TIME,CONTACT_ID,SOFT_DELETED") + csvRows).joinToString("\n", postfix = "\n"))
      }
      val json = File(hostDir, "contacts.json")
      prepare(csv, json, hostDir)

      val postgres = TestContainersSessionListener.postgres
      val workDir = "/tmp/delius-note-correction-${UUID.randomUUID()}"
      postgres.copyFileToContainer(MountableFile.forHostPath(SCRIPT.absolutePath), "$workDir/script.sql")
      postgres.copyFileToContainer(MountableFile.forHostPath(json.absolutePath), "$workDir/contacts.json")

      val result = psql("-f", "script.sql", workDir = workDir)
      assertThat(result.exitCode).withFailMessage { "psql failed:\n${result.stdout}\n${result.stderr}" }.isZero()

      return postgres.copyFileFromContainer("$workDir/matched.jsonl") { it.readAllBytes().decodeToString() }
        .lines()
        .filter { it.isNotBlank() }
        .map { mapper.readTree(it) }
    } finally {
      hostDir.deleteRecursively()
    }
  }

  private fun prepare(csv: File, json: File, dir: File) {
    val log = File(dir, "prepare.log")
    val process = try {
      ProcessBuilder("python3", PREPARE.absolutePath, "prepare", csv.absolutePath, json.absolutePath)
        .redirectErrorStream(true)
        .redirectOutput(log)
        .start()
    } catch (e: IOException) {
      abort<Unit>("python3 not available: ${e.message}")
      return
    }
    if (!process.waitFor(1, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      throw AssertionError("prepare timed out:\n${log.readText()}")
    }
    assertThat(process.exitValue()).withFailMessage { "prepare failed:\n${log.readText()}" }.isZero()
  }

  /** psql in the container, in the same read-only session run_delius_note_correction.sh opens against prod. */
  private fun psql(vararg args: String, workDir: String = "/tmp"): Container.ExecResult {
    val postgres = TestContainersSessionListener.postgres
    return postgres.execInContainer(
      "sh",
      "-c",
      "cd $workDir && PGPASSWORD='${postgres.password}' PGOPTIONS='-c default_transaction_read_only=on' " +
        "psql -X -q -v ON_ERROR_STOP=1 -U '${postgres.username}' -d '${postgres.databaseName}' " +
        args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" },
    )
  }

  private fun JsonNode.text(field: String): String = get(field).asString()

  private fun offender(crn: String): Offender = offenderRepository.save(
    Offender(
      uuid = UUID.randomUUID(),
      crn = crn,
      practitionerId = "BARRY.WHITE",
      status = OffenderStatus.VERIFIED,
      firstCheckin = LocalDate.of(2026, 1, 1),
      checkinInterval = Duration.ofDays(7),
      mode = CheckinMode.SCHEDULED,
      createdAt = Instant.now(),
      createdBy = "BARRY.WHITE",
      updatedAt = Instant.now(),
      contactPreference = ContactPreference.PHONE,
    ),
  )

  private fun submittedCheckin(offender: Offender, submittedAt: String, surveyResponse: Map<String, Any>): OffenderCheckin = checkinRepository.saveAndFlush(
    OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = CheckinStatus.SUBMITTED,
      dueDate = LocalDate.of(2026, 4, 22),
      createdAt = Instant.parse("2026-04-22T00:00:00Z"),
      createdBy = offender.practitionerId,
      submittedAt = Instant.parse(submittedAt),
      surveyResponse = surveyResponse,
    ),
  )

  private fun tableCounts(): Map<String, Int> = listOf("offender_v2", "offender_checkin_v2", "event_audit_log_v2").associateWith {
    jdbcTemplate.queryForObject("select count(*) from $it", Int::class.java)!!
  }

  companion object {
    private val SCRIPT = File("scripts/delius_note_correction.sql")
    private val PREPARE = File("scripts/delius_note_correction.py")
  }
}
