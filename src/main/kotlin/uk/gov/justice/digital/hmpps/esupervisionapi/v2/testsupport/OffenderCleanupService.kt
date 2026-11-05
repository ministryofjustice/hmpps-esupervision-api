package uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.dao.DataAccessException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3PurgeResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.util.UUID

data class OffenderCleanupResponse(
  val crn: String,
  /** Rows deleted per table, in the order they were deleted. */
  val rowsDeleted: Map<String, Int>,
  /** S3 objects removed for good: every version and delete marker of each key. */
  val s3ObjectsDeleted: Int,
  /** S3 objects, or whole check-ins' worth of them, that could not be removed. The database rows are gone regardless. */
  val s3Failures: Int,
)

/**
 * Removes a person on probation, and everything held for them, once their case has been deleted
 * in NDelius. For the e2e tests, which create cases in NDelius, onboard them here, and delete the
 * case afterwards.
 *
 * Refuses while NDelius still knows the CRN. That keeps it to cases that are really gone, and away
 * from the long-lived test cases the e2e suite and developers share in dev.
 *
 * Not transactional itself: the NDelius check runs before the database transaction opens, so no
 * locks are held over an HTTP call, and S3 is cleared only once the delete has committed.
 */
@Service
@ConditionalOnExpression(TEST_SUPPORT_ENABLED)
class OffenderCleanupService(
  private val offenderRepository: OffenderRepository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val cleanupRepository: OffenderCleanupRepository,
  private val s3UploadService: S3UploadService,
) {

  fun deleteOffenderNotInDelius(rawCrn: String): OffenderCleanupResponse {
    val crn = rawCrn.trim().uppercase()
    if (!CRN_PATTERN.matches(crn)) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a valid CRN")
    }
    if (offenderRepository.findByCrn(crn).isEmpty) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "No person on probation with CRN $crn")
    }

    // getContactDetailsStrict returns null only for a 404 from NDelius; any other failure throws,
    // and must never be read as "not in NDelius".
    val inDelius = try {
      ndiliusApiClient.getContactDetailsStrict(crn) != null
    } catch (e: Exception) {
      LOGGER.warn("Test support: could not check NDelius for crn={}: {}", crn, e.javaClass.simpleName)
      throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not check NDelius for $crn. Nothing was deleted.", e)
    }
    if (inDelius) {
      throw ResponseStatusException(
        HttpStatus.CONFLICT,
        "$crn still exists in NDelius. Delete the case there first. Nothing was deleted.",
      )
    }

    val deletion = deleteWhenUnlocked(crn)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No person on probation with CRN $crn")
    LOGGER.info("Test support: deleted crn={} rows={}", crn, deletion.rowsDeleted)

    val (s3Deleted, s3Failed) = deleteS3Objects(deletion.offenderUuid, deletion.checkinUuids)
    if (s3Failed > 0) {
      LOGGER.warn("Test support: crn={} deleted, but {} S3 deletes failed", crn, s3Failed)
    }
    return OffenderCleanupResponse(crn, deletion.rowsDeleted, s3Deleted, s3Failed)
  }

  /**
   * The repository locks with NOWAIT, so it fails at once if the app holds the person's rows.
   * Retry briefly; an app transaction is short. Never waiting on a lock is what keeps this from
   * deadlocking with, and possibly aborting, a user's request.
   */
  private fun deleteWhenUnlocked(crn: String): OffenderDeletion? {
    repeat(LOCK_ATTEMPTS - 1) { attempt ->
      try {
        return cleanupRepository.deleteByCrn(crn)
      } catch (e: DataAccessException) {
        if (!e.isLockNotAvailable()) throw e
        LOGGER.info("Test support: crn={} is locked by the app, retrying (attempt {})", crn, attempt + 1)
        Thread.sleep(LOCK_BACKOFF_MS * (attempt + 1))
      }
    }
    return try {
      cleanupRepository.deleteByCrn(crn)
    } catch (e: DataAccessException) {
      if (!e.isLockNotAvailable()) throw e
      throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "$crn is being updated by the app. Nothing was deleted; try again shortly.",
        e,
      )
    }
  }

  /** Best effort: the rows are already gone, so a failure here is reported, not thrown. */
  private fun deleteS3Objects(offenderUuid: UUID, checkinUuids: List<UUID>): Pair<Int, Int> {
    var deleted = 0
    var failed = 0
    fun purge(what: String, block: () -> S3PurgeResult) {
      try {
        val result = block()
        deleted += result.keysDeleted
        failed += result.keysFailed
      } catch (e: Exception) {
        LOGGER.warn("Test support: could not delete S3 objects for {}: {}", what, e.javaClass.simpleName)
        failed++
      }
    }
    purge("offender $offenderUuid") { s3UploadService.purgeSetupPhoto(offenderUuid) }
    checkinUuids.forEach { uuid -> purge("checkin $uuid") { s3UploadService.purgeCheckinObjects(uuid) } }
    return deleted to failed
  }

  /**
   * NOWAIT failing, Postgres SQL state 55P03. Spring does not translate that state on this path -
   * it arrives as UncategorizedSQLException - so look for it rather than for an exception type.
   */
  private fun DataAccessException.isLockNotAvailable(): Boolean = this is PessimisticLockingFailureException ||
    generateSequence<Throwable>(this) { it.cause }.any { (it as? java.sql.SQLException)?.sqlState == "55P03" }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(OffenderCleanupService::class.java)
    private const val LOCK_ATTEMPTS = 5
    private const val LOCK_BACKOFF_MS = 200L
  }
}
