package uk.gov.justice.digital.hmpps.esupervisionapi.scripts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.Container
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.TestContainersSessionListener
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAudit
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinMode
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Runs scripts/practitioner_contact_list.sql for real: with psql, inside the test Postgres
 * container, against the schema Liquibase has fully migrated.
 *
 * The script is plain SQL outside the application, so nothing else would notice if a migration
 * renamed or dropped a column it reads -- the recurring export would just fail on the day. This
 * pins the columns it depends on and the behaviour the export relies on.
 *
 * Every run uses the session run_practitioner_export.sh opens against prod, with
 * default_transaction_read_only=on, so Postgres refuses any write. A script that tried one --
 * even a temporary table -- would fail every test here, not just the one that asks.
 *
 * psql runs inside the container rather than on the host because the script uses psql
 * meta-commands (\o, \pset) that JDBC cannot execute, and the postgres image ships psql.
 */
class PractitionerContactListSqlTest : IntegrationTestBase() {

  @Autowired lateinit var offenderRepository: OffenderRepository

  @Autowired lateinit var offenderSetupRepository: OffenderSetupRepository

  @Autowired lateinit var eventAuditRepository: EventAuditRepository

  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  private val mapper = jacksonObjectMapper()

  @AfterEach
  fun cleanUp() {
    eventAuditRepository.deleteAll()
    offenderSetupRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  @Test
  fun `exports every offender whatever its status, with the stored username upper-cased`() {
    offender("Z900001", "barry.white", OffenderStatus.VERIFIED)
    offender("Z900002", "JO.BLOGGS", OffenderStatus.INACTIVE)
    offender("Z900003", "SAM.PATEL", OffenderStatus.INITIAL)

    val crns = runScript().crns()

    assertThat(crns.keys).containsExactlyInAnyOrder("Z900001", "Z900002", "Z900003")
    assertThat(crns.getValue("Z900001").text("storedUsername")).isEqualTo("BARRY.WHITE")
    assertThat(crns.getValue("Z900002").text("status")).isEqualTo("INACTIVE")
    assertThat(crns.getValue("Z900003").text("status")).isEqualTo("INITIAL")
  }

  @Test
  fun `takes PDU and region from the newest audit row that has each`() {
    offender("Z900001", "BARRY.WHITE")
    audit("Z900001", "2026-01-01T00:00:00Z", pdu = "Bolton PDU", region = "North West")
    // Newer: the PDU description came back null. The Bolton PDU must survive it.
    audit("Z900001", "2026-02-01T00:00:00Z", pdu = null, region = "Greater Manchester")
    // Newest: the NDelius lookup failed, so nothing at all. Must not blank either column.
    audit("Z900001", "2026-03-01T00:00:00Z", pdu = null, region = null)

    val row = runScript().crns().getValue("Z900001")

    assertThat(row.text("pdu")).isEqualTo("Bolton PDU")
    assertThat(row.text("region")).isEqualTo("Greater Manchester")
  }

  @Test
  fun `leaves PDU and region null for a CRN with no audit geography`() {
    offender("Z900001", "BARRY.WHITE")

    val row = runScript().crns().getValue("Z900001")

    assertThat(row.get("pdu").isNull).isTrue()
    assertThat(row.get("region").isNull).isTrue()
  }

  @Test
  fun `writes PDU names containing commas as valid JSON`() {
    offender("Z900001", "BARRY.WHITE")
    audit("Z900001", "2026-01-01T00:00:00Z", pdu = "Cumbria, and Lancashire PDU", region = "North West")

    assertThat(runScript().crns().getValue("Z900001").text("pdu")).isEqualTo("Cumbria, and Lancashire PDU")
  }

  @Test
  fun `collects usernames from every source, without service accounts`() {
    val barry = offender("Z900001", "BARRY.WHITE")
    offenderSetupRepository.save(
      OffenderSetup(uuid = UUID.randomUUID(), offender = barry, practitionerId = "setup.colleague", createdAt = Instant.now()),
    )
    audit("Z900001", "2026-01-01T00:00:00Z", practitioner = "AUDIT.PERSON")
    audit("Z900001", "2026-01-02T00:00:00Z", practitioner = "SYSTEM")

    val usernames = runScript().usernames()

    assertThat(usernames).contains("BARRY.WHITE", "SETUP.COLLEAGUE", "AUDIT.PERSON")
    assertThat(usernames).doesNotContain("SYSTEM")
  }

  @Test
  fun `writes the usernames as CSV with a header, one row per username`() {
    val barry = offender("Z900001", "BARRY.WHITE")
    offenderSetupRepository.save(
      OffenderSetup(uuid = UUID.randomUUID(), offender = barry, practitionerId = "setup.colleague", createdAt = Instant.now()),
    )

    val lines = runScript().usernamesCsv.lines()

    assertThat(lines.first()).isEqualTo("username,mentions,sources")
    assertThat(lines).contains("SETUP.COLLEAGUE,1,offender_setup_v2")
  }

  @Test
  fun `the session the script runs in really does refuse writes`() {
    // Guards the premise of every other test here, and of the export itself: were the read-only
    // option not taking effect, a script that wrote would pass them all unnoticed.
    val result = psql("-c", "CREATE TEMP TABLE should_not_exist (id int)")

    assertThat(result.exitCode).isNotZero()
    assertThat(result.stderr).contains("read-only transaction")
  }

  @Test
  fun `does not change the database`() {
    offender("Z900001", "BARRY.WHITE")
    audit("Z900001", "2026-01-01T00:00:00Z", pdu = "Bolton PDU", region = "North West")
    val before = tableCounts()

    runScript()

    assertThat(tableCounts()).isEqualTo(before)
  }

  // ---------------------------------------------------------------------------------------------

  private class ScriptOutput(val crnsJsonl: String, val usernamesCsv: String)

  /** Copies the script into the container, runs it with psql from a clean directory, and reads back what it wrote. */
  private fun runScript(): ScriptOutput {
    val postgres = TestContainersSessionListener.postgres
    val workDir = "/tmp/practitioner-export-${UUID.randomUUID()}"
    postgres.copyFileToContainer(MountableFile.forHostPath(SCRIPT.absolutePath), "$workDir/script.sql")

    val result = psql("-f", "script.sql", workDir = workDir)
    assertThat(result.exitCode).withFailMessage { "psql failed:\n${result.stdout}\n${result.stderr}" }.isZero()

    fun read(name: String) = postgres.copyFileFromContainer("$workDir/$name") { it.readAllBytes().decodeToString() }
    return ScriptOutput(read("practitioner_crns.jsonl"), read("practitioner_usernames.csv"))
  }

  /** psql in the container, in the same read-only session run_practitioner_export.sh opens against prod. */
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

  /** The CRN rows, keyed by CRN. Rows other test classes left behind are ignored. */
  private fun ScriptOutput.crns(): Map<String, JsonNode> = crnsJsonl.lines()
    .filter { it.isNotBlank() }
    .map { mapper.readTree(it) }
    .filter { it.text("crn").startsWith(CRN_PREFIX) }
    .associateBy { it.text("crn") }

  private fun ScriptOutput.usernames(): Set<String> = usernamesCsv.lines()
    .drop(1)
    .filter { it.isNotBlank() }
    .map { it.substringBefore(',') }
    .toSet()

  private fun JsonNode.text(field: String): String = get(field).asString()

  private fun offender(crn: String, practitioner: String, status: OffenderStatus = OffenderStatus.VERIFIED): Offender = offenderRepository.save(
    Offender(
      uuid = UUID.randomUUID(),
      crn = crn,
      practitionerId = practitioner,
      status = status,
      firstCheckin = LocalDate.of(2026, 1, 1),
      checkinInterval = Duration.ofDays(7),
      mode = CheckinMode.SCHEDULED,
      createdAt = Instant.now(),
      createdBy = practitioner,
      updatedAt = Instant.now(),
      contactPreference = ContactPreference.PHONE,
    ),
  )

  private fun audit(crn: String, at: String, pdu: String? = null, region: String? = null, practitioner: String = "BARRY.WHITE") {
    eventAuditRepository.save(
      EventAudit(
        eventType = "CHECKIN_SUBMITTED",
        occurredAt = Instant.parse(at),
        crn = crn,
        practitionerId = practitioner,
        pduCode = pdu?.let { "PDU1" },
        pduDescription = pdu,
        providerCode = region?.let { "N50" },
        providerDescription = region,
      ),
    )
  }

  private fun tableCounts(): Map<String, Int> = listOf("offender_v2", "offender_setup_v2", "event_audit_log_v2").associateWith {
    jdbcTemplate.queryForObject("select count(*) from $it", Int::class.java)!!
  }

  companion object {
    private val SCRIPT = File("scripts/practitioner_contact_list.sql")
    private const val CRN_PREFIX = "Z9"
  }
}
