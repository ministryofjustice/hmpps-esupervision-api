package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLogsHintV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLogsV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.JobLogV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdiliusBatchFetchException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PartialCheckinCreatedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.BatchCheckinCreationException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinCreationService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.CheckinIneligibilityReason
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.activeEventNumber
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin.checkinIneligibilityReason
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderDeactivationV2Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import kotlin.jvm.optionals.getOrNull
import kotlin.math.min

private class JobMetrics {
  var processed = 0
  var created = 0
  var deactivated = 0
  var errors = 0
  var chunks = 0
}

private fun JobMetrics.info(logger: Logger, jobId: Long, duration: Duration) {
  logger.info(
    "Checkin Creation Job(id={}) completed: processed={}, created={}, deactivated={}, failed={}, chunks={}, took={}",
    jobId,
    this.processed,
    this.created,
    this.deactivated,
    this.errors,
    this.chunks,
    duration,
  )
}

/**
 * V2 Checkin Creation Job
 * Creates checkins for verified offenders based on their schedule
 * Core feature - always enabled
 */
@Component
class V2CheckinCreationJob(
  private val clock: Clock,
  private val offenderRepository: OffenderV2Repository,
  private val ndiliusApiClient: INdiliusApiClient,
  private val checkinCreationService: CheckinCreationService,
  private val offenderDeactivationV2Service: OffenderDeactivationV2Service,
  private val jobLogRepository: JobLogV2Repository,
  @PersistenceContext private val entityManager: EntityManager,
  @param:Value("\${app.scheduling.v2-checkin-creation.chunk-size}") private val chunkSize: Int,
  @param:Value("\${app.scheduling.checkin-notification.window:72h}") private val checkinWindow: Duration,
) {

  private val checkinWindowPeriod = Period.ofDays(checkinWindow.toDays().toInt())

  @Scheduled(cron = "\${app.scheduling.v2-checkin-creation.cron}")
  @SchedulerLock(
    name = "V2 Checkin Creation Job",
    lockAtLeastFor = "PT5S",
    lockAtMostFor = "PT30M",
  )
  fun process() {
    val now = clock.instant()
    val today = LocalDate.now(clock)
    val logEntry = jobLogRepository.saveAndFlush(JobLogV2(jobType = "V2_CHECKIN_CREATION", createdAt = now))
    LOGGER.info("Checkin Creation Job(id={}) started: creating checkins for date={}", logEntry.id, today)
    val metrics = JobMetrics()

    try {
      val upperBound = today.plusDays(1)
      val finalChunkSize = min(chunkSize, INdiliusApiClient.MAX_BATCH_SIZE)

      do {
        val pageRequest = PageRequest.of(0, finalChunkSize)
        val chunk = offenderRepository.findEligibleForCheckinCreation(lowerBoundInclusive = today, upperBound, pageRequest)
        if (chunk.isEmpty) {
          break
        }

        metrics.chunks += 1
        LOGGER.info("Processing chunk {} with {} offenders", metrics.chunks, chunk.size)

        try {
          val contactDetailsMap = getContactDetailsMap(chunk.map { it.crn }.toList(), metrics.chunks)
          val checkinsToCreate = ArrayList<Pair<OffenderCheckinV2, PartialCheckinCreatedEvent>>(contactDetailsMap.size)
          val infoByCrn = chunk.associateBy { it.crn }
          for (crn in contactDetailsMap.keys) {
            val info = infoByCrn[crn]!!
            val contactDetails = contactDetailsMap[crn]!!
            val ineligibility = checkinIneligibilityReason(info, contactDetails)
            if (ineligibility != null) {
              deactivateOffender(checkinIneligibilityReason(info, contactDetails)!!, info, contactDetails, metrics)
            } else {
              prepareCheckin(info, today, contactDetails)?.let { checkinsToCreate.add(it) }
            }
          }

          metrics.processed += contactDetailsMap.size
          val batchInsertStart = System.currentTimeMillis()
          checkinCreationService.createCheckins(checkinsToCreate)
          logCreatedCheckins(checkinsToCreate, today, batchInsertStart)
          metrics.created += checkinsToCreate.size
        } catch (e: NdiliusBatchFetchException) {
          metrics.errors += e.crns.size // already logged elsewhere
        } catch (e: BatchCheckinCreationException) {
          LOGGER.warn("Failed to create {} checkins for chunk {}", e.checkins.size, metrics.chunks, e)
          metrics.errors += e.checkins.size
        }
      } while (chunk.hasNext())
    } catch (e: Exception) {
      LOGGER.warn("Checkin Creation Job(id={}) failed, metrics={}", logEntry.id, metrics, e)
    }

    val endTime = clock.instant()
    logEntry.endedAt = endTime
    jobLogRepository.saveAndFlush(logEntry)

    metrics.info(LOGGER, jobId = logEntry.id, Duration.between(now, endTime))
  }

  private fun prepareCheckin(
    info: OffenderV2Repository.IOffenderCheckinCreationInfo,
    today: LocalDate,
    contactDetails: ContactDetails,
  ): Pair<OffenderCheckinV2, PartialCheckinCreatedEvent>? {
    val offenderRef = offenderRepository.getReferenceById(info.id)
    val checkin = checkinCreationService.prepareCheckinForOffender(offenderRef, today)
    val eventNumber = activeEventNumber(info, contactDetails)
    var result: Pair<OffenderCheckinV2, PartialCheckinCreatedEvent>? = null
    if (eventNumber != null) {
      val event = PartialCheckinCreatedEvent(
        offenderId = info.id,
        practitionerId = info.practitionerId,
        checkin = CheckinV2Dto(
          uuid = checkin.uuid,
          dueDate = checkin.dueDate,
          status = checkin.status,
          createdAt = checkin.createdAt,
          createdBy = checkin.createdBy,
          crn = info.crn,
          personalDetails = contactDetails,
          checkinLogs = CheckinLogsV2Dto(CheckinLogsHintV2.OMITTED, emptyList()),
        ),
        offenderContactPreference = info.contactPreference,
        currentEvent = eventNumber,
      )
      result = Pair(checkin, event)
    }
    LOGGER.debug(
      "Will {} checkin for CRN {}: currentEvent={}",
      if (eventNumber != null) "create" else "not create",
      info.crn,
      eventNumber,
    )
    return result
  }

  private fun deactivateOffender(
    ineligibility: CheckinIneligibilityReason,
    offenderInfo: OffenderV2Repository.IOffenderCheckinCreationInfo,
    contactDetails: ContactDetails,
    metrics: JobMetrics,
  ) {
    // POP is no longer eligible (no active events, or in reset) - stop their online check-ins.
    // Isolate failures per-offender so one bad deactivation doesn't abort the whole run.
    LOGGER.info("Deactivating CRN {} instead of creating checkin: {}", offenderInfo.crn, ineligibility.name)
    val offender = offenderRepository.findById(offenderInfo.id).getOrNull() ?: return
    try {
      offenderDeactivationV2Service.deactivateOffender(
        offender,
        ineligibility.auditNote,
        contactDetails,
        auditEventType = ineligibility.auditEventType,
      )
      metrics.deactivated += 1
    } catch (e: Exception) {
      LOGGER.warn("Failed to deactivate CRN {} in chunk {}", offenderInfo.crn, metrics.chunks, e)
      metrics.errors += 1
    }
  }

  private fun logCreatedCheckins(checkins: List<Pair<OffenderCheckinV2, PartialCheckinCreatedEvent>>, dueDate: LocalDate, start: Long) {
    val sb = StringBuilder()
    for ((checkin, event) in checkins) {
      sb.append("(CRN=").append(event.checkin.crn)
      sb.append(", eventNumber=").append(event.currentEvent ?: "NULL")
      sb.append(", checkin=").append(checkin.uuid).append(")")
    }
    LOGGER.info(
      "Checkins created: {}, with due date {}, took {}, (CRN,eventNumber,uuid): {}",
      checkins.size,
      dueDate,
      "${(System.currentTimeMillis() - start) / 1000} s",
      sb.toString(),
    )
  }

  private fun getContactDetailsMap(
    crns: List<String>,
    numChunks: Int,
  ): Map<String, ContactDetails> {
    val contactDetailsMap = try {
      ndiliusApiClient.getContactDetailsForMultiple(crns).associateBy { it.crn }
    } catch (e: NdiliusBatchFetchException) {
      LOGGER.warn("Failed to fetch contact details for chunk {}", numChunks, e)
      throw e
    }

    val missing = crns.toSet().minus(contactDetailsMap.keys)
    if (missing.isNotEmpty()) {
      LOGGER.info("Contact details not found for {} CRNs in chunk {}. CRNS: {}", missing.size, numChunks, missing)
    }

    return contactDetailsMap
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(V2CheckinCreationJob::class.java)
  }
}
