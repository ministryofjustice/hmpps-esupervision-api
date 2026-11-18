package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.ResourceNotFoundException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.jvm.optionals.getOrElse

@Service
class EsupervisionEligibilityDataProvider(
  private val offenderRepository: OffenderRepository,
  private val pilotPractitionerLookup: PilotPractitionerLookup,
  @Qualifier("eligibilityDataFetchExecutor") private val eligibilityDataFetchExecutor: Executor,
) : EligibilityDataProvider {
  override val sourceKey: String
    get() = "ESUP"

  /**
   * Only reached when a caller didn't pre-fill ESUP. With no practitioner to hand, the pilot
   * practitioner is the one the person is allocated to in online check ins.
   */
  override fun fetch(crn: CRN): CompletableFuture<Map<String, Any?>> = CompletableFuture.supplyAsync(
    {
      val offender = offenderRepository.findByCrn(crn).getOrElse {
        throw ResourceNotFoundException("Could not fetch eligibility details from $sourceKey for CRN: $crn")
      }
      offender.eligibilityData(pilotPractitionerLookup.hasHadPilotCase(offender.practitionerId))
    },
    eligibilityDataFetchExecutor,
  )
}

/**
 * Answers "has this practitioner ever had a pilot case" - a person in online check ins before the
 * pilot cut-off (`offender_v2.in_pilot`), allocated to them now or set up by them at any point -
 * for the PRACTITIONER_PILOT data point. Setups that never completed don't count.
 */
@Service
class PilotPractitionerLookup(private val offenderRepository: OffenderRepository) {
  fun hasHadPilotCase(practitionerId: ExternalUserId?): Boolean {
    val id = practitionerId?.trim()
    if (id.isNullOrEmpty()) return false
    return offenderRepository.isAllocatedPilotCase(id) || offenderRepository.hasSetUpPilotCase(id)
  }

  /** For batch callers: every practitioner who has had a pilot case. Check with [isPilotPractitioner]. */
  fun allPilotPractitionerIds(): Set<String> = offenderRepository.findPilotCaseAllocatedPractitionerIds() + offenderRepository.findPilotCaseSetupPractitionerIds()

  companion object {
    fun isPilotPractitioner(pilotPractitionerIds: Set<String>, practitionerId: ExternalUserId?): Boolean = !practitionerId.isNullOrBlank() && practitionerId.trim() in pilotPractitionerIds
  }
}

/**
 * @param practitionerPilot whether the practitioner the check is made for has ever had a pilot case;
 *   see [PilotPractitionerLookup].
 */
fun Offender.eligibilityData(practitionerPilot: Boolean): Map<String, Any?> = mapOf(
  "PILOT_USER" to this.inPilot,
  "PRACTITIONER_PILOT" to practitionerPilot,
)

/** ESUP data for a CRN not yet set up in online check ins. */
fun nonOnboardedEligibilityData(practitionerPilot: Boolean): Map<String, Any?> = mapOf(
  "PILOT_USER" to false,
  "PRACTITIONER_PILOT" to practitionerPilot,
)
