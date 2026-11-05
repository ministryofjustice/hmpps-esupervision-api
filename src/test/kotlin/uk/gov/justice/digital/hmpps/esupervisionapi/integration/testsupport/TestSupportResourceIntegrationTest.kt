package uk.gov.justice.digital.hmpps.esupervisionapi.integration.testsupport

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResend
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinNoteResendRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAudit
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.LogEntryType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLog
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderEventLogRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3PurgeResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport.OffenderCleanupRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport.OffenderCleanupService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport.TestSupportResource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val UI_ROLE = "ROLE_ESUPERVISION__ESUPERVISION_UI"

@TestPropertySource(properties = ["app.test-support.enabled=true", "app.env=DEV"])
class TestSupportResourceIntegrationTest : IntegrationTestBase() {

  @Autowired lateinit var jdbc: JdbcTemplate

  @Autowired lateinit var offenderRepository: OffenderRepository

  @Autowired lateinit var setupRepository: OffenderSetupRepository

  @Autowired lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired lateinit var eventLogRepository: OffenderEventLogRepository

  @Autowired lateinit var notificationRepository: GenericNotificationRepository

  @Autowired lateinit var auditRepository: EventAuditRepository

  @Autowired lateinit var resendRepository: CheckinNoteResendRepository

  @Autowired lateinit var cleanupRepository: OffenderCleanupRepository

  @Autowired lateinit var transactionManager: PlatformTransactionManager

  @MockitoBean lateinit var ndeliusApiClient: INdiliusApiClient

  @MockitoBean lateinit var s3UploadService: S3UploadService

  private val orphan = "T100001"
  private val bystander = "T100002"
  private val sharedListCrn = "T100003"

  @org.junit.jupiter.api.BeforeEach
  fun stubS3() {
    whenever(s3UploadService.purgeSetupPhoto(any())).thenReturn(S3PurgeResult(keysDeleted = 1, keysFailed = 0))
    whenever(s3UploadService.purgeCheckinObjects(any())).thenReturn(S3PurgeResult(keysDeleted = 2, keysFailed = 0))
  }

  @AfterEach
  fun cleanUp() {
    cleanupRepository.deleteByCrn(orphan)
    cleanupRepository.deleteByCrn(bystander)
    cleanupRepository.deleteByCrn(sharedListCrn)
    jdbc.update("delete from question_list where author = 'test-support'")
    reset(ndeliusApiClient, s3UploadService)
  }

  private fun delete(crn: String, roles: List<String> = listOf(UI_ROLE)) = webTestClient.delete()
    .uri("/v2/test-support/offenders/crn/$crn")
    .headers(setAuthorisation(roles = roles))
    .exchange()

  @Test
  fun `deletes every row held for the person, and nobody else's`() {
    val seeded = seedPerson(orphan)
    seedPerson(bystander)
    // Captured now: once the person is gone, their check-ins and setups can no longer be found
    // through offender_v2, and counting that way would show zero whether or not the rows went.
    val orphanKeys = keysFor(orphan)
    val bystanderKeys = keysFor(bystander)
    val bystanderBefore = rowsFor(bystanderKeys)
    assertThat(rowsFor(orphanKeys).filterValues { it == 0 }.keys)
      .describedAs("the seed should put something in every table, or the test proves nothing")
      .isEmpty()

    delete(orphan).expectStatus().isOk
      .expectBody()
      .jsonPath("$.crn").isEqualTo(orphan)
      .jsonPath("$.rowsDeleted.offender_v2").isEqualTo(1)
      .jsonPath("$.rowsDeleted.offender_checkin_v2").isEqualTo(1)
      .jsonPath("$.rowsDeleted.question_list").isEqualTo(1)
      .jsonPath("$.s3ObjectsDeleted").isEqualTo(3)
      .jsonPath("$.s3Failures").isEqualTo(0)

    assertThat(rowsFor(orphanKeys)).allSatisfy { _, n -> assertThat(n).isZero() }
    assertThat(rowsFor(bystanderKeys)).isEqualTo(bystanderBefore)
    verify(s3UploadService).purgeSetupPhoto(seeded.offenderUuid)
    verify(s3UploadService).purgeCheckinObjects(seeded.checkinUuid)

    // The person's own unnamed list goes, with its items; the named list and one still assigned to
    // someone else stay.
    assertThat(listExists(seeded.ownListId)).isFalse()
    assertThat(itemCount(seeded.ownListId)).isZero()
    assertThat(listExists(seeded.namedListId)).isTrue()
    assertThat(listExists(seeded.sharedListId)).isTrue()
  }

  @Test
  fun `refuses while NDelius still knows the CRN, and deletes nothing`() {
    seedPerson(orphan)
    val keys = keysFor(orphan)
    val before = rowsFor(keys)
    whenever(ndeliusApiClient.getContactDetailsStrict(orphan)).thenReturn(mock<ContactDetails>())

    delete(orphan).expectStatus().isEqualTo(409)

    assertThat(rowsFor(keys)).isEqualTo(before)
    verify(s3UploadService, never()).purgeSetupPhoto(any())
  }

  @Test
  fun `refuses when NDelius cannot be asked, rather than reading that as not found`() {
    seedPerson(orphan)
    val keys = keysFor(orphan)
    val before = rowsFor(keys)
    doThrow(RuntimeException("NDelius down")).whenever(ndeliusApiClient).getContactDetailsStrict(orphan)

    delete(orphan).expectStatus().isEqualTo(503)

    assertThat(rowsFor(keys)).isEqualTo(before)
  }

  @Test
  fun `S3 failures are reported but do not undo the delete`() {
    seedPerson(orphan)
    doThrow(RuntimeException("S3 down")).whenever(s3UploadService).purgeSetupPhoto(any())

    delete(orphan).expectStatus().isOk
      .expectBody().jsonPath("$.s3Failures").isEqualTo(1)

    assertThat(offenderRepository.findByCrn(orphan)).isEmpty
  }

  @Test
  fun `an unknown CRN is not found, without asking NDelius`() {
    delete("T999999").expectStatus().isNotFound
    verify(ndeliusApiClient, never()).getContactDetailsStrict(any())
  }

  @Test
  fun `something that is not a CRN is rejected`() {
    delete("not-a-crn").expectStatus().isBadRequest
  }

  @Test
  fun `requires a token`() {
    webTestClient.delete().uri("/v2/test-support/offenders/crn/$orphan").exchange().expectStatus().isUnauthorized
  }

  @Test
  fun `requires the role`() {
    seedPerson(orphan)
    delete(orphan, roles = listOf("ROLE_SOMETHING_ELSE")).expectStatus().isForbidden
    assertThat(offenderRepository.findByCrn(orphan)).isPresent
  }

  /**
   * NOWAIT plus retry: while the app holds the person's row, the cleanup gives up with a 503 rather
   * than waiting on the lock, which could deadlock with the app and abort a user's request.
   */
  @Test
  fun `gives up rather than waiting while the app holds the person's rows`() {
    seedPerson(orphan)
    givesUpWhileLocked("select id from offender_v2 where crn = ? for update", orphan)
  }

  /** The app can lock an assignment without the person, to point it at a new list. */
  @Test
  fun `gives up rather than waiting while the app holds a question list assignment`() {
    seedPerson(orphan)
    givesUpWhileLocked(
      "select 1 from question_list_assignment where offender_id = (select id from offender_v2 where crn = ?) for update",
      orphan,
    )
  }

  /**
   * Rows locked neither by NOWAIT nor through the person: the outbox relay marks items sent in a
   * transaction of its own, and question_list_item rows go by cascade. The lock timeout covers these.
   */
  @Test
  fun `gives up rather than waiting while the app holds an outbox item`() {
    val seeded = seedPerson(orphan)
    givesUpWhileLocked(
      "select 1 from outbox_items where type::text = 'CHECKIN_SUBMITTED' and entity_id = (select id from offender_checkin_v2 where uuid = ?) for update",
      seeded.checkinUuid,
    )
  }

  @Test
  fun `gives up rather than waiting while the app holds a row that goes by cascade`() {
    val seeded = seedPerson(orphan)
    givesUpWhileLocked("select 1 from question_list_item where question_list_id = ? for update", seeded.ownListId)
  }

  /** Holds [lockSql] in another transaction: the cleanup answers 503 and changes nothing, then works once it is released. */
  private fun givesUpWhileLocked(lockSql: String, vararg args: Any) {
    val keys = keysFor(orphan)
    val before = rowsFor(keys)
    val locked = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = thread {
      TransactionTemplate(transactionManager).executeWithoutResult {
        assertThat(jdbc.queryForList(lockSql, *args)).describedAs("the lock should hold a row").isNotEmpty()
        locked.countDown()
        release.await(30, TimeUnit.SECONDS)
      }
    }
    try {
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
      delete(orphan).expectStatus().isEqualTo(503)
      assertThat(rowsFor(keys)).isEqualTo(before)
    } finally {
      release.countDown()
      holder.join()
    }

    // Once the app lets go, it goes through.
    delete(orphan).expectStatus().isOk
    assertThat(rowsFor(keys)).allSatisfy { _, n -> assertThat(n).isZero() }
  }

  /**
   * outbox_items.entity_id has no foreign key, so only the type says what a row points at. A new
   * type would leave its rows behind; fail here so whoever adds one maps it in the outbox delete.
   */
  @Test
  fun `every outbox item type is handled`() {
    val types = jdbc.queryForList("select unnest(enum_range(null::outboxitemtype))::text", String::class.java).toSet()
    assertThat(types).isEqualTo(OffenderCleanupRepository.HANDLED_OUTBOX_TYPES)
  }

  /**
   * A new table with a foreign key into the person's tables would make the delete fail (or, with a
   * cascade, silently take more than intended). Fail here instead, so whoever adds it updates
   * [OffenderCleanupRepository] and scripts/orphan_crn_cleanup.sql.
   */
  @Test
  fun `every foreign key into the deleted tables is handled`() {
    val foreignKeys = jdbc.queryForList(
      """
      select distinct conrelid::regclass::text || ' -> ' || confrelid::regclass::text
      from pg_constraint
      where contype = 'f'
        and confrelid::regclass::text = any (?)
      """,
      String::class.java,
      OffenderCleanupRepository.DELETED_TABLES.toTypedArray(),
    ).toSet()

    assertThat(foreignKeys).isEqualTo(OffenderCleanupRepository.HANDLED_FOREIGN_KEYS)
  }

  private data class Seeded(
    val offenderUuid: UUID,
    val checkinUuid: UUID,
    val ownListId: Long,
    val namedListId: Long,
    val sharedListId: Long,
  )

  /** One person with a row in every table the cleanup covers. */
  private fun seedPerson(crn: String): Seeded {
    val now = Instant.now()
    val offender = offenderRepository.save(offenderTemplate.copy(crn = crn, uuid = UUID.randomUUID()).toEntity())
    val setup = setupRepository.save(
      OffenderSetup(uuid = UUID.randomUUID(), offender = offender, practitionerId = offender.practitionerId, createdAt = now),
    )
    val checkin = checkinRepository.save(
      OffenderCheckin(
        uuid = UUID.randomUUID(),
        offender = offender,
        status = CheckinStatus.SUBMITTED,
        dueDate = LocalDate.now(),
        createdAt = now,
        createdBy = offender.practitionerId,
        submittedAt = now,
      ),
    )
    val logEntry = eventLogRepository.save(
      OffenderEventLog(
        comment = "note",
        createdAt = now,
        logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
        practitioner = offender.practitionerId,
        uuid = UUID.randomUUID(),
        checkin = checkin.id,
        offender = offender,
      ),
    )
    notificationRepository.save(
      GenericNotification(
        notificationId = UUID.randomUUID(),
        eventType = "TEST",
        recipientType = "OFFENDER",
        channel = "EMAIL",
        offenderId = offender.id,
        reference = "ref",
        createdAt = now,
      ),
    )
    auditRepository.save(EventAudit(eventType = "CHECKIN_SUBMITTED", occurredAt = now, crn = crn, checkinUuid = checkin.uuid))
    resendRepository.save(CheckinNoteResend(checkin = checkin.uuid))
    offenderRepository.flush()

    // Tables without an entity, or whose rows the app writes only from jobs and triggers.
    jdbc.update("insert into setup_event_backfill_v2 (offender_id) values (?)", offender.id)
    jdbc.update(
      "insert into migration_events_to_send (id, checkin) values ((select coalesce(max(id), 0) + 1 from migration_events_to_send), ?)",
      checkin.uuid,
    )
    jdbc.update(
      """
      insert into checkin_delius_event_v2 (event_id, event_type, detail_id, checkin_uuid, crn, occurred_at)
      values (?, 'V2_CHECKIN_ANNOTATED', ?, ?, ?, now())
      """,
      UUID.randomUUID(),
      logEntry.uuid,
      checkin.uuid,
      crn,
    )
    // Custom questions: the unnamed list made for this person's assignment (as
    // upsert_custom_question_list makes one), with an item; a named, shared list; and an unnamed
    // list that a long-lived case also uses.
    val ownListId = newList(name = null)
    jdbc.update(
      "insert into question_list_item (question_list_id, question_id, position) values (?, (select min(id) from question), 1)",
      ownListId,
    )
    val namedListId = newList(name = "test-support-$crn")
    val sharedListId = newList(name = null)
    jdbc.update("insert into question_list_assignment (question_list_id, offender_id, checkin_id) values (?, ?, ?)", ownListId, offender.id, checkin.id)
    jdbc.update("insert into question_list_assignment (question_list_id, offender_id, checkin_id) values (?, ?, ?)", namedListId, offender.id, checkin.id)
    jdbc.update("insert into question_list_assignment (question_list_id, offender_id, checkin_id) values (?, ?, ?)", sharedListId, offender.id, checkin.id)
    // The long-lived case may already hold a pending assignment (one per person), from the other seed.
    jdbc.update(
      "insert into question_list_assignment (question_list_id, offender_id, checkin_id) values (?, ?, null) on conflict do nothing",
      sharedListId,
      sharedListUser().id,
    )
    jdbc.update(
      """
      insert into outbox_items (type, entity_id) values
        ('CHECKIN_SUBMITTED', ?), ('CHECKIN_ANNOTATED', ?), ('OFFENDER_DEACTIVATED', ?)
      on conflict do nothing
      """,
      checkin.id,
      logEntry.id,
      setup.id,
    )
    return Seeded(offender.uuid, checkin.uuid, ownListId, namedListId, sharedListId)
  }

  private fun newList(name: String?): Long = jdbc.queryForObject(
    "insert into question_list (author, name) values ('test-support', ?) returning id",
    Long::class.java,
    name,
  )!!

  /** A long-lived case, never cleaned up by these tests' CRNs, that shares a question list. */
  private fun sharedListUser() = offenderRepository.findByCrn(sharedListCrn).orElseGet {
    offenderRepository.saveAndFlush(offenderTemplate.copy(crn = sharedListCrn, uuid = UUID.randomUUID()).toEntity())
  }

  private fun listExists(id: Long) = jdbc.queryForObject("select count(*) from question_list where id = ?", Int::class.java, id) == 1

  private fun itemCount(id: Long) = jdbc.queryForObject("select count(*) from question_list_item where question_list_id = ?", Int::class.java, id)

  /** The ids and UUIDs a person's rows are found by, captured so they can still be counted once the person is gone. */
  private data class PersonKeys(
    val crn: String,
    val offenderIds: List<Long>,
    val checkinIds: List<Long>,
    val checkinUuids: List<UUID>,
    val setupIds: List<Long>,
    val logEntryIds: List<Long>,
  )

  private fun keysFor(crn: String): PersonKeys {
    fun <T : Any> list(sql: String, type: Class<T>, vararg args: Any): List<T> = jdbc.queryForList(sql, type, *args).filterNotNull()
    val offenderIds = list("select id from offender_v2 where crn = ?", Long::class.java, crn)
    val offenders = sqlList(offenderIds)
    val checkinIds = list("select id from offender_checkin_v2 where offender_id in ($offenders)", Long::class.java)
    return PersonKeys(
      crn = crn,
      offenderIds = offenderIds,
      checkinIds = checkinIds,
      checkinUuids = list("select uuid from offender_checkin_v2 where offender_id in ($offenders)", UUID::class.java),
      setupIds = list("select id from offender_setup_v2 where offender_id in ($offenders)", Long::class.java),
      logEntryIds = list(
        "select id from offender_event_log_v2 where offender_id in ($offenders) or checkin in (${sqlList(checkinIds)})",
        Long::class.java,
      ),
    )
  }

  /** "in (...)" contents; "null" for none, which matches nothing. */
  private fun sqlList(values: List<Any>) = values.joinToString { if (it is UUID) "'$it'" else "$it" }.ifEmpty { "null" }

  /** Rows held for a person, per table, found by the keys captured in [keysFor]. */
  private fun rowsFor(keys: PersonKeys): Map<String, Int> {
    val offenders = sqlList(keys.offenderIds)
    val checkins = sqlList(keys.checkinIds)
    val checkinUuids = sqlList(keys.checkinUuids)
    val setups = sqlList(keys.setupIds)
    val logEntries = sqlList(keys.logEntryIds)
    val counts = mapOf(
      "offender_v2" to "select count(*) from offender_v2 where id in ($offenders) or crn = '${keys.crn}'",
      "offender_setup_v2" to "select count(*) from offender_setup_v2 where id in ($setups)",
      "offender_checkin_v2" to "select count(*) from offender_checkin_v2 where id in ($checkins)",
      "offender_event_log_v2" to "select count(*) from offender_event_log_v2 where id in ($logEntries)",
      "generic_notification_v2" to "select count(*) from generic_notification_v2 where offender_id in ($offenders)",
      "setup_event_backfill_v2" to "select count(*) from setup_event_backfill_v2 where offender_id in ($offenders)",
      "question_list_assignment" to
        "select count(*) from question_list_assignment where offender_id in ($offenders) or checkin_id in ($checkins)",
      "event_audit_log_v2" to "select count(*) from event_audit_log_v2 where crn = '${keys.crn}'",
      "checkin_note_resend" to "select count(*) from checkin_note_resend where checkin in ($checkinUuids)",
      "migration_events_to_send" to "select count(*) from migration_events_to_send where checkin in ($checkinUuids)",
      "checkin_delius_event_v2" to
        "select count(*) from checkin_delius_event_v2 where crn = '${keys.crn}' or checkin_uuid in ($checkinUuids)",
      "outbox_items" to """
        select count(*) from outbox_items
        where (type::text like 'CHECKIN_%' and type::text <> 'CHECKIN_ANNOTATED' and entity_id in ($checkins))
           or (type::text = 'CHECKIN_ANNOTATED' and entity_id in ($logEntries))
           or (type::text like 'OFFENDER_%' and entity_id in ($setups))
      """,
    )
    return counts.mapValues { (_, sql) -> jdbc.queryForObject(sql, Int::class.java)!! }
  }
}

/**
 * The beans must be absent, not merely unreachable. An HTTP 404 alone would not show that: with the
 * endpoint present, an unknown CRN answers 404 too.
 */
private fun assertTestSupportAbsent(context: ApplicationContext, webTestClient: WebTestClient, auth: (HttpHeaders) -> Unit) {
  assertThat(context.getBeansOfType(TestSupportResource::class.java)).isEmpty()
  assertThat(context.getBeansOfType(OffenderCleanupService::class.java)).isEmpty()
  assertThat(context.getBeansOfType(OffenderCleanupRepository::class.java)).isEmpty()
  webTestClient.delete()
    .uri("/v2/test-support/offenders/crn/T100001")
    .headers(auth)
    .exchange()
    .expectStatus().isNotFound
}

/** Off unless switched on: the default test context has the flag unset. */
class TestSupportResourceDisabledIntegrationTest : IntegrationTestBase() {
  @Autowired lateinit var context: ApplicationContext

  @Test
  fun `the endpoint does not exist when test support is off`() {
    assertTestSupportAbsent(context, webTestClient, setAuthorisation(roles = listOf(UI_ROLE)))
  }
}

/** The flag alone is not enough: set by mistake outside dev, it still creates nothing. */
@TestPropertySource(properties = ["app.test-support.enabled=true", "app.env=PREPROD"])
class TestSupportResourceOutsideDevIntegrationTest : IntegrationTestBase() {
  @Autowired lateinit var context: ApplicationContext

  @Test
  fun `the endpoint does not exist outside dev, even with the flag on`() {
    assertTestSupportAbsent(context, webTestClient, setAuthorisation(roles = listOf(UI_ROLE)))
  }
}
