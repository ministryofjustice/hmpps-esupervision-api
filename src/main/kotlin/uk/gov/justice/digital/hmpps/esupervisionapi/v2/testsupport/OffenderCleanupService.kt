package uk.gov.justice.digital.hmpps.esupervisionapi.v2.testsupport

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import java.util.UUID

data class OffenderCleanupResponse(
  val crn: String,
  /** Rows deleted per table, in the order they were deleted. */
  val rowsDeleted: Map<String, Int>,
  val s3ObjectsDeleted: Int,
  /** S3 deletes that failed. The database rows are gone regardless; these objects are left behind. */
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

    val deletion = cleanupRepository.deleteByCrn(crn)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No person on probation with CRN $crn")
    LOGGER.info("Test support: deleted crn={} rows={}", crn, deletion.rowsDeleted)

    val (s3Deleted, s3Failed) = deleteS3Objects(deletion.offenderUuid, deletion.checkinUuids)
    if (s3Failed > 0) {
      LOGGER.warn("Test support: crn={} deleted, but {} S3 deletes failed", crn, s3Failed)
    }
    return OffenderCleanupResponse(crn, deletion.rowsDeleted, s3Deleted, s3Failed)
  }

  /** Best effort: the rows are already gone, so a failure here is reported, not thrown. */
  private fun deleteS3Objects(offenderUuid: UUID, checkinUuids: List<UUID>): Pair<Int, Int> {
    var deleted = 0
    var failed = 0
    try {
      s3UploadService.deleteSetupPhoto(offenderUuid)
      deleted++
    } catch (e: Exception) {
      LOGGER.warn("Test support: could not delete setup photo for offender {}: {}", offenderUuid, e.javaClass.simpleName)
      failed++
    }
    for (checkinUuid in checkinUuids) {
      try {
        deleted += s3UploadService.deleteCheckinObjects(checkinUuid)
      } catch (e: Exception) {
        LOGGER.warn("Test support: could not delete S3 objects for checkin {}: {}", checkinUuid, e.javaClass.simpleName)
        failed++
      }
    }
    return deleted to failed
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(OffenderCleanupService::class.java)
  }
}
