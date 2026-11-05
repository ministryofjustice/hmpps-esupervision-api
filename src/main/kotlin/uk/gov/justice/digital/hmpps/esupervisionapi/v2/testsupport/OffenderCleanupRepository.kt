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
 * Every table with a foreign key into offender_v2, offender_checkin_v2, offender_setup_v2 or
 * offender_event_log_v2 is covered, plus the tables that refer to them by id, UUID or CRN without a
 * foreign key. OffenderCleanupRepositoryIntegrationTest fails if a new foreign key appears, so a
 * new table cannot be missed silently. Same deletes as scripts/orphan_crn_cleanup.sql.
 *
 * Left alone: question_list (shared between people), the monthly stats (recomputed from
 * event_audit_log_v2), and the legacy v1 / migration tables.
 */
@Repository
@ConditionalOnExpression(TEST_SUPPORT_ENABLED)
class OffenderCleanupRepository(
  private val jdbc: NamedParameterJdbcTemplate,
) {

  @Transactional
  fun deleteByCrn(crn: String): OffenderDeletion? {
    val params = mapOf("crn" to crn)

    // Lock the person and their check-ins first, so the app cannot add a check-in, change a status
    // (whose trigger writes outbox_items) or log an event for them part way through. outbox_items
    // and checkin_delius_event_v2 have no foreign keys, so such rows would be left pointing at
    // nothing; inserts that do have one wait on these locks.
    val offender = jdbc.query(
      "select id, uuid from offender_v2 where crn = :crn for update",
      params,
    ) { rs, _ -> rs.getLong("id") to rs.getObject("uuid", UUID::class.java) }.singleOrNull() ?: return null
    val (offenderId, offenderUuid) = offender

    val p = mapOf("offenderId" to offenderId, "crn" to crn)
    val checkinUuids = jdbc.query(
      "select uuid from offender_checkin_v2 where offender_id = :offenderId order by id for update",
      p,
    ) { rs, _ -> rs.getObject("uuid", UUID::class.java) }

    val rows = linkedMapOf<String, Int>()
    fun delete(table: String, sql: String) {
      rows[table] = jdbc.update(sql, p)
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
    /**
     * Tables with a foreign key into the ones this deletes from, and so handled here. The
     * integration test compares this against the live schema.
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
    )

    private const val CHECKIN_IDS = "select id from offender_checkin_v2 where offender_id = :offenderId"
    private const val CHECKIN_UUIDS = "select uuid from offender_checkin_v2 where offender_id = :offenderId"
    private const val EVENT_LOG_IDS =
      "select id from offender_event_log_v2 where offender_id = :offenderId or checkin in ($CHECKIN_IDS)"
  }
}
