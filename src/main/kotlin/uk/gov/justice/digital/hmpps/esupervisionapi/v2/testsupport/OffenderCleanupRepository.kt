package uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** What was removed for one person: rows per table, in delete order, and the UUIDs their S3 objects live under. */
data class OffenderDeletion(
  val offenderUuid: UUID,
  val checkinUuids: List<UUID>,
  val rowsDeleted: Map<String, Int>,
)

/**
 * Deletes everything held for one person on probation, in one transaction.
 *
 * Every table with a foreign key into a table deleted from is covered, plus the tables that refer
 * to the person by id, UUID or CRN without a foreign key. TestSupportResourceIntegrationTest fails
 * if a new foreign key or outbox item type appears ([HANDLED_FOREIGN_KEYS], [HANDLED_OUTBOX_TYPES]),
 * so a new table cannot be missed silently. Same deletes as scripts/orphan_crn_cleanup.sql, plus
 * the person's own question lists.
 *
 * Left alone: named question lists (the Default list and other shared ones), bespoke questions
 * (another practitioner's list can reuse one by id), the monthly stats (recomputed from
 * event_audit_log_v2), and the legacy v1 / migration tables.
 */
@Repository
@ConditionalOnExpression(TEST_SUPPORT_ENABLED)
class OffenderCleanupRepository(
  private val jdbc: NamedParameterJdbcTemplate,
) {

  /**
   * Locks with NOWAIT: if the app holds any of these rows, this fails at once with
   * SQL state 55P03 and the caller retries, rather than waiting. Waiting could deadlock
   * with app transactions, which lock the person and their check-ins in either order, and Postgres
   * might then abort the user's request rather than this one. Never waiting means never
   * deadlocking.
   *
   * Once the person row is locked, nothing with a foreign key to it - a new check-in, setup, event
   * log entry or notification - can be inserted until this commits. Rows the app writes by CRN or
   * UUID alone (event_audit_log_v2, checkin_delius_event_v2) are not blocked: a job that read the
   * person before this ran can still write one afterwards. In dev that leaves at most a stray
   * audit or relay row, which nothing reads as a person.
   */
  @Transactional
  fun deleteByCrn(crn: String): OffenderDeletion? {
    val offender = jdbc.query(
      "select id, uuid from offender_v2 where crn = :crn for update nowait",
      mapOf("crn" to crn),
    ) { rs, _ -> rs.getLong("id") to rs.getObject("uuid", UUID::class.java) }.singleOrNull() ?: return null
    val (offenderId, offenderUuid) = offender

    val p = mapOf("offenderId" to offenderId, "crn" to crn)
    val checkinUuids = jdbc.query(
      "select uuid from offender_checkin_v2 where offender_id = :offenderId order by id for update nowait",
      p,
    ) { rs, _ -> rs.getObject("uuid", UUID::class.java) }

    // Before their assignments go: the lists this person's custom questions were put in.
    val questionListIds = jdbc.queryForList(
      "select distinct question_list_id from question_list_assignment where offender_id = :offenderId or checkin_id in ($CHECKIN_IDS)",
      p,
      Long::class.java,
    )

    val rows = linkedMapOf<String, Int>()
    fun delete(table: String, sql: String, params: Map<String, Any> = p) {
      rows[table] = jdbc.update(sql, params)
    }

    // outbox_items.entity_id has no foreign key; what it points at depends on the type.
    delete(
      "outbox_items",
      """
      delete from outbox_items
      where (type::text in ('CHECKIN_CREATED', 'CHECKIN_SUBMITTED', 'CHECKIN_REVIEWED', 'CHECKIN_EXPIRED')
             and entity_id in ($CHECKIN_IDS))
         or (type::text = 'CHECKIN_ANNOTATED' and entity_id in ($EVENT_LOG_IDS))
         or (type::text in ('OFFENDER_SETUP_COMPLETE', 'OFFENDER_DEACTIVATED', 'OFFENDER_REACTIVATED')
             and entity_id in (select id from offender_setup_v2 where offender_id = :offenderId))
      """,
    )
    delete(
      "checkin_delius_event_v2",
      "delete from checkin_delius_event_v2 where crn = :crn or checkin_uuid in ($CHECKIN_UUIDS)",
    )
    delete("checkin_note_resend", "delete from checkin_note_resend where checkin in ($CHECKIN_UUIDS)")
    delete("migration_events_to_send", "delete from migration_events_to_send where checkin in ($CHECKIN_UUIDS)")
    delete(
      "question_list_assignment",
      "delete from question_list_assignment where offender_id = :offenderId or checkin_id in ($CHECKIN_IDS)",
    )
    // Each custom-questions assignment gets a new, unnamed list of its own. Delete those once no
    // one else's assignment uses them; their question_list_item rows go with them (ON DELETE
    // CASCADE). Named lists are shared and stay.
    rows["question_list"] = if (questionListIds.isEmpty()) {
      0
    } else {
      jdbc.update(
        """
        delete from question_list ql
        where ql.id in (:listIds)
          and ql.name is null
          and not exists (select 1 from question_list_assignment a where a.question_list_id = ql.id)
        """,
        mapOf("listIds" to questionListIds),
      )
    }
    delete("offender_event_log_v2", "delete from offender_event_log_v2 where id in ($EVENT_LOG_IDS)")
    delete("generic_notification_v2", "delete from generic_notification_v2 where offender_id = :offenderId")
    delete("setup_event_backfill_v2", "delete from setup_event_backfill_v2 where offender_id = :offenderId")
    delete("offender_setup_v2", "delete from offender_setup_v2 where offender_id = :offenderId")
    delete("offender_checkin_v2", "delete from offender_checkin_v2 where offender_id = :offenderId")
    delete("event_audit_log_v2", "delete from event_audit_log_v2 where crn = :crn")
    delete("offender_v2", "delete from offender_v2 where id = :offenderId")

    return OffenderDeletion(offenderUuid, checkinUuids, rows)
  }

  companion object {
    /** Every table [deleteByCrn] deletes from. */
    val DELETED_TABLES = setOf(
      "outbox_items", "checkin_delius_event_v2", "checkin_note_resend", "migration_events_to_send",
      "question_list_assignment", "question_list", "offender_event_log_v2", "generic_notification_v2",
      "setup_event_backfill_v2", "offender_setup_v2", "offender_checkin_v2", "event_audit_log_v2",
      "offender_v2",
    )

    /**
     * Every foreign key into [DELETED_TABLES], as "child -> parent". Each child is either deleted
     * from here first or, for question_list_item, removed by its ON DELETE CASCADE. The integration
     * test compares this against the live schema.
     */
    val HANDLED_FOREIGN_KEYS = setOf(
      "offender_setup_v2 -> offender_v2",
      "offender_checkin_v2 -> offender_v2",
      "generic_notification_v2 -> offender_v2",
      "offender_event_log_v2 -> offender_v2",
      "question_list_assignment -> offender_v2",
      "setup_event_backfill_v2 -> offender_v2",
      "offender_event_log_v2 -> offender_checkin_v2",
      "question_list_assignment -> offender_checkin_v2",
      "question_list_assignment -> question_list",
      "question_list_item -> question_list",
    )

    /** The outbox item types the outbox delete maps to an entity. */
    val HANDLED_OUTBOX_TYPES = setOf(
      "CHECKIN_CREATED",
      "CHECKIN_SUBMITTED",
      "CHECKIN_REVIEWED",
      "CHECKIN_EXPIRED",
      "CHECKIN_ANNOTATED",
      "OFFENDER_SETUP_COMPLETE",
      "OFFENDER_DEACTIVATED",
      "OFFENDER_REACTIVATED",
    )

    private const val CHECKIN_IDS = "select id from offender_checkin_v2 where offender_id = :offenderId"
    private const val CHECKIN_UUIDS = "select uuid from offender_checkin_v2 where offender_id = :offenderId"
    private const val EVENT_LOG_IDS =
      "select id from offender_event_log_v2 where offender_id = :offenderId or checkin in ($CHECKIN_IDS)"
  }
}
