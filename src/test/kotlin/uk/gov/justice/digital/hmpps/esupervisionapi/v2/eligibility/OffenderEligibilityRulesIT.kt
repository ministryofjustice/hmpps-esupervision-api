package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine.Companion.DEFAULT_RULE_SET
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * The full eligibility rules (OFFENDER_ELIGIBILITY), as stored in the database, read as a decision
 * table. Each test describes one person in plain facts and states who decides them and how.
 *
 * The rules run in order and the first one to decide wins:
 *
 *  1. Records, whatever the tier - no active sentence, suspended (in reset), not on a supervision
 *     package, recalled, no tier, provisional tier, final third, no practitioner.
 *  2. The practitioner's device restriction answer.
 *  3. Tier D to G - eligible.
 *  4. A pilot case themselves (`IS_PILOT_USER`) - eligible. Unchanged pending a decision.
 *  5. Tier C - eligible only if their practitioner has ever had a pilot case.
 *  6. Tier A/B on an accredited programme - ruled out by a youth sentence or early engagement,
 *     otherwise eligible. The pilot doesn't apply.
 *  7. Tier A/B not on a programme - eligible only if their practitioner has ever had a pilot case.
 *     A youth sentence or early engagement doesn't apply.
 *
 * Steps 5 to 7 are the manage check ins UI's own rules, which these replace. The steps that only
 * hold on one branch are rules with an "applies when" condition.
 */
class OffenderEligibilityRulesIT : IntegrationTestBase() {

  @Autowired
  private lateinit var engine: EligibilityEvaluationEngine

  @Autowired
  private lateinit var ruleRepository: EligibilityRuleRepository

  @Test
  fun `only the branch rules have an applies-when condition`() {
    val conditions = ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(DEFAULT_RULE_SET)
      .filter { it.appliesWhenSource != null }
      .associate { it.code to "${it.appliesWhenSource}.${it.appliesWhenDataPoint} ${it.appliesWhenOperator} ${it.appliesWhenComparisonValue}" }

    assertThat(conditions).containsExactlyInAnyOrderEntriesOf(
      mapOf(
        "PRACTITIONER_HAS_PILOT_CASE_TIER_C" to "TIER.TIER EQUALS C",
        "ON_YOUTH_SENTENCE" to "MANUAL.ACCREDITED_PROGRAMME EQUALS true",
        "IN_EARLY_ENGAGEMENT" to "MANUAL.ACCREDITED_PROGRAMME EQUALS true",
      ),
    )
  }

  @Nested
  inner class `Ruled out by their records, whatever their tier` {
    @Test
    fun `no active sentence`() {
      assertThat(decide(tierD.copy(hasActiveSentence = false))).isEqualTo(notEligible("HAS_ACTIVE_EVENT"))
    }

    @Test
    fun `suspended (in reset)`() {
      assertThat(decide(tierD.copy(suspended = true))).isEqualTo(notEligible("IS_CONTACT_SUSPENDED"))
    }

    @Test
    fun `not on a supervision package`() {
      assertThat(decide(tierD.copy(onSupervisionPackage = false))).isEqualTo(notEligible("ON_SUPERVISION_PACKAGE"))
    }

    @Test
    fun recalled() {
      assertThat(decide(tierD.copy(recalled = true))).isEqualTo(notEligible("IS_RECALLED"))
    }

    @Test
    fun `no tier yet`() {
      assertThat(decide(tierD.copy(tier = "MISSING"))).isEqualTo(notEligible("IS_TIER_MISSING"))
    }

    @Test
    fun `no tier yet, and so no supervision package yet - told about the tier`() {
      assertThat(decide(tierD.copy(tier = "MISSING", onSupervisionPackage = false))).isEqualTo(notEligible("IS_TIER_MISSING"))
    }

    @Test
    fun `provisional tier`() {
      assertThat(decide(tierD.copy(tierProvisional = true))).isEqualTo(notEligible("IS_TIER_PROVISIONAL"))
    }

    @Test
    fun `in the final third`() {
      assertThat(decide(tierD.copy(inFinalThird = true))).isEqualTo(notEligible("IN_FINAL_THIRD"))
    }

    @Test
    fun `no practitioner assigned`() {
      assertThat(decide(tierD.copy(practitionerAssigned = false))).isEqualTo(notEligible("IS_PRACTITIONER_ASSIGNED"))
    }

    @Test
    fun `a pilot practitioner doesn't help`() {
      assertThat(decide(tierC.copy(practitionerHasHadPilotCase = true, recalled = true))).isEqualTo(notEligible("IS_RECALLED"))
    }

    @Test
    fun `only the first reason is given, in rule order`() {
      val recalledInFinalThirdWithNoPractitioner = tierD.copy(recalled = true, inFinalThird = true, practitionerAssigned = false)

      assertThat(decide(recalledInFinalThirdWithNoPractitioner)).isEqualTo(notEligible("IS_RECALLED"))
    }
  }

  @Nested
  inner class `The practitioner's answers` {
    @Test
    fun `a device restriction rules out any tier`() {
      assertThat(decide(tierD.copy(deviceRestriction = true))).isEqualTo(notEligible("HAS_DEVICE_RESTRICTION"))
      assertThat(decide(tierC.copy(deviceRestriction = true, practitionerHasHadPilotCase = true))).isEqualTo(notEligible("HAS_DEVICE_RESTRICTION"))
      assertThat(decide(tierBOnProgramme.copy(deviceRestriction = true))).isEqualTo(notEligible("HAS_DEVICE_RESTRICTION"))
    }

    @Test
    fun `without them, anyone their records don't rule out can't be decided yet`() {
      assertThat(decide(tierD.copy(answered = false))).isEqualTo(NeedsAnswers)
      assertThat(decide(tierC.copy(answered = false))).isEqualTo(NeedsAnswers)
    }

    @Test
    fun `without them, someone their records rule out is still ruled out`() {
      assertThat(decide(tierD.copy(answered = false, recalled = true))).isEqualTo(notEligible("IS_RECALLED"))
    }
  }

  @Nested
  inner class `Tier D to G` {
    @Test
    fun `are eligible`() {
      listOf("D", "E", "F", "G").forEach { tier ->
        assertThat(decide(tierD.copy(tier = tier))).describedAs("Tier $tier").isEqualTo(eligible("IS_TIER_D_TO_G"))
      }
    }

    @Test
    fun `early engagement doesn't apply`() {
      assertThat(decide(tierD.copy(inEarlyEngagement = true))).isEqualTo(eligible("IS_TIER_D_TO_G"))
    }
  }

  @Nested
  inner class `Tier C` {
    @Test
    fun `are eligible if their practitioner has ever had a pilot case`() {
      assertThat(decide(tierC.copy(practitionerHasHadPilotCase = true))).isEqualTo(eligible("PRACTITIONER_HAS_PILOT_CASE_TIER_C"))
    }

    @Test
    fun `are not eligible otherwise`() {
      assertThat(decide(tierC)).isEqualTo(notEligible("IS_TIER_C"))
    }

    @Test
    fun `early engagement doesn't apply`() {
      assertThat(decide(tierC.copy(practitionerHasHadPilotCase = true, inEarlyEngagement = true))).isEqualTo(eligible("PRACTITIONER_HAS_PILOT_CASE_TIER_C"))
    }
  }

  @Nested
  inner class `Tier A or B on an accredited programme` {
    @Test
    fun `are eligible`() {
      assertThat(decide(tierBOnProgramme)).isEqualTo(eligible("IN_EARLY_ENGAGEMENT"))
      assertThat(decide(tierBOnProgramme.copy(tier = "A"))).isEqualTo(eligible("IN_EARLY_ENGAGEMENT"))
    }

    @Test
    fun `are not eligible on a youth sentence`() {
      assertThat(decide(tierBOnProgramme.copy(youthSentence = true))).isEqualTo(notEligible("ON_YOUTH_SENTENCE"))
    }

    @Test
    fun `are not eligible in early engagement`() {
      assertThat(decide(tierBOnProgramme.copy(inEarlyEngagement = true))).isEqualTo(notEligible("IN_EARLY_ENGAGEMENT"))
    }

    @Test
    fun `a pilot practitioner doesn't get round a youth sentence or early engagement`() {
      val pilot = tierBOnProgramme.copy(practitionerHasHadPilotCase = true)

      assertThat(decide(pilot.copy(youthSentence = true))).isEqualTo(notEligible("ON_YOUTH_SENTENCE"))
      assertThat(decide(pilot.copy(inEarlyEngagement = true))).isEqualTo(notEligible("IN_EARLY_ENGAGEMENT"))
    }

    @Test
    fun `a youth sentence is reported before early engagement`() {
      assertThat(decide(tierBOnProgramme.copy(youthSentence = true, inEarlyEngagement = true))).isEqualTo(notEligible("ON_YOUTH_SENTENCE"))
    }
  }

  @Nested
  inner class `Tier A or B not on an accredited programme` {
    @Test
    fun `are eligible if their practitioner has ever had a pilot case`() {
      assertThat(decide(tierBNotOnProgramme.copy(practitionerHasHadPilotCase = true))).isEqualTo(eligible("PRACTITIONER_HAS_PILOT_CASE_TIER_A_B"))
    }

    @Test
    fun `are not eligible otherwise`() {
      assertThat(decide(tierBNotOnProgramme)).isEqualTo(notEligible("ON_ACCREDITED_PROGRAMME"))
    }

    @Test
    fun `a youth sentence and early engagement don't apply`() {
      val pilot = tierBNotOnProgramme.copy(practitionerHasHadPilotCase = true)

      assertThat(decide(pilot.copy(youthSentence = true))).isEqualTo(eligible("PRACTITIONER_HAS_PILOT_CASE_TIER_A_B"))
      assertThat(decide(pilot.copy(inEarlyEngagement = true))).isEqualTo(eligible("PRACTITIONER_HAS_PILOT_CASE_TIER_A_B"))
    }
  }

  @Nested
  inner class `Pilot cases themselves` {
    // IS_PILOT_USER is unchanged while it's decided whether pilot cases are held to the same criteria
    @Test
    fun `are eligible in any tier A to C, before the tier rules`() {
      assertThat(decide(tierC.copy(wasPilotCase = true))).isEqualTo(eligible("IS_PILOT_USER"))
      assertThat(decide(tierBOnProgramme.copy(wasPilotCase = true, youthSentence = true))).isEqualTo(eligible("IS_PILOT_USER"))
    }

    @Test
    fun `are still ruled out by their records`() {
      assertThat(decide(tierC.copy(wasPilotCase = true, inFinalThird = true))).isEqualTo(notEligible("IN_FINAL_THIRD"))
    }
  }

  /**
   * The nightly sync has no practitioner answers, so it assumes everyone is on an accredited
   * programme with no youth sentence or device restriction (see OffenderEligibilitySyncJob).
   */
  @Nested
  inner class `The nightly sync's assumed answers` {
    private fun assumed(person: Person) = person.copy(onProgramme = true, youthSentence = false, deviceRestriction = false)

    @Test
    fun `don't affect Tier C, which is decided before the programme rules`() {
      assertThat(decide(assumed(tierC))).isEqualTo(notEligible("IS_TIER_C"))
      assertThat(decide(assumed(tierC.copy(practitionerHasHadPilotCase = true)))).isEqualTo(eligible("PRACTITIONER_HAS_PILOT_CASE_TIER_C"))
    }

    @Test
    fun `judge Tier A or B as if on a programme`() {
      assertThat(decide(assumed(tierBNotOnProgramme))).isEqualTo(eligible("IN_EARLY_ENGAGEMENT"))
      assertThat(decide(assumed(tierBNotOnProgramme.copy(inEarlyEngagement = true)))).isEqualTo(notEligible("IN_EARLY_ENGAGEMENT"))
    }
  }

  /** One person, in the facts the rules look at. The defaults pass every records rule. */
  private data class Person(
    val tier: String,
    val tierProvisional: Boolean = false,
    val hasActiveSentence: Boolean = true,
    val suspended: Boolean = false,
    val practitionerAssigned: Boolean = true,
    val onSupervisionPackage: Boolean = true,
    val recalled: Boolean = false,
    val inFinalThird: Boolean = false,
    val inEarlyEngagement: Boolean = false,
    val wasPilotCase: Boolean = false,
    val practitionerHasHadPilotCase: Boolean = false,
    // the practitioner's answers; programme and youth sentence are only asked of Tier A/B
    val answered: Boolean = true,
    val deviceRestriction: Boolean = false,
    val onProgramme: Boolean? = null,
    val youthSentence: Boolean? = null,
  )

  private val tierD = Person(tier = "D")
  private val tierC = Person(tier = "C")
  private val tierBOnProgramme = Person(tier = "B", onProgramme = true, youthSentence = false)
  private val tierBNotOnProgramme = Person(tier = "B", onProgramme = false, youthSentence = false)

  private sealed interface Decision
  private data class Decided(val outcome: EligibilityCheckOutcome, val by: String?) : Decision
  private data object NeedsAnswers : Decision

  private fun eligible(by: String) = Decided(EligibilityCheckOutcome.ELIGIBLE, by)
  private fun notEligible(by: String) = Decided(EligibilityCheckOutcome.INELIGIBLE, by)

  private fun decide(person: Person): Decision {
    val data: MutableMap<String, Map<String, Any?>> = mutableMapOf(
      "NDELIUS" to mapOf(
        "ACTIVE_EVENT" to (if (person.hasActiveSentence) "event 1" else null),
        "CONTACT_SUSPENDED" to person.suspended,
        "PRACTITIONER_ASSIGNED" to person.practitionerAssigned,
      ),
      "SUP-PACK" to mapOf(
        "ON_PACKAGE" to person.onSupervisionPackage,
        "RECALLED" to person.recalled,
        "FINAL_THIRD" to person.inFinalThird,
        "EARLY_ENGAGEMENT" to person.inEarlyEngagement,
      ),
      "TIER" to mapOf("TIER" to person.tier, "PROVISIONAL" to person.tierProvisional),
      "ESUP" to mapOf("PILOT_USER" to person.wasPilotCase, "PRACTITIONER_PILOT" to person.practitionerHasHadPilotCase),
    )
    if (person.answered) {
      data["MANUAL"] = EligibilityAnswers(
        accreditedProgramme = person.onProgramme,
        deviceRestriction = person.deviceRestriction,
        youthSentence = person.youthSentence,
      ).eligibilityData()
    }
    val cache = data.mapValues { CompletableFuture.completedFuture<Map<String, Any?>>(it.value) }

    return try {
      val result = engine.evaluate("X123456", DEFAULT_RULE_SET, cache).join()
      Decided(result.outcome, result.triggeredRuleCode)
    } catch (e: CompletionException) {
      val cause = e.cause
      if (cause is EligibilityDataUnavailableException && cause.source == "MANUAL") NeedsAnswers else throw e
    }
  }
}
