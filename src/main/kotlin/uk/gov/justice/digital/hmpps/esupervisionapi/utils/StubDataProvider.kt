package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OrganizationalUnit
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.ArnsWidget
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.RiskInSituation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.CustodyDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackageDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierApiVersion
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierDetails
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

typealias CRN = String

interface StubDataProvider {
  fun provideCase(crn: CRN): ContactDetails
  fun provideTierDetails(crn: CRN, version: TierApiVersion): TierDetails
  fun provideArnsWidget(crn: CRN): ArnsWidget
  fun provideSupervisionPackageDetails(crn: CRN): SupervisionPackageDetails
}

class DefaultStubDataProvider : StubDataProvider {
  override fun provideCase(crn: CRN): ContactDetails = ContactDetails(
    crn = crn,
    name = Name(
      forename = "John",
      surname = "Smith",
    ),
    dateOfBirth = LocalDate.of(1980, 1, 1),
    mobile = "07700900123",
    email = "john.smith@example.com",
    practitioner = PractitionerDetails(
      code = "N01A001",
      name = Name(
        forename = "Sarah",
        surname = "Johnson",
      ),
      email = "sarah.johnson@justice.gov.uk",
      localAdminUnit = OrganizationalUnit(
        code = "LAU001",
        description = "London Central Local Admin Unit",
      ),
      probationDeliveryUnit = OrganizationalUnit(
        code = "PDU001",
        description = "London Probation Delivery Unit",
      ),
      provider = OrganizationalUnit(
        code = "PRV001",
        description = "London Probation Service",
      ),
      unallocated = false,
      username = "sarah.johnson",
    ),
  )

  override fun provideTierDetails(crn: String, version: TierApiVersion): TierDetails = TierDetails(
    tierScore = if (version == TierApiVersion.V3) "D" else "D2",
    calculationId = UUID.randomUUID(),
    calculationDate = LocalDate.of(2026, 1, 1),
    changeReason = "A registration was added",
    provisional = if (version == TierApiVersion.V3) false else null,
  )

  override fun provideArnsWidget(crn: CRN): ArnsWidget = ArnsWidget(
    overallRisk = "VERY_HIGH",
    assessedOn = LocalDate.of(2026, 1, 1),
    riskInCommunity = RiskInSituation(
      public = "HIGH",
      children = "LOW",
      knownAdult = "MEDIUM",
      staff = "VERY_HIGH",
      prisoners = null,
    ),
    riskInCustody = RiskInSituation(
      public = "HIGH",
      children = "LOW",
      knownAdult = "MEDIUM",
      staff = "VERY_HIGH",
      prisoners = "VERY_HIGH",
    ),
  )

  override fun provideSupervisionPackageDetails(crn: CRN): SupervisionPackageDetails = SupervisionPackageDetails(
    supervisionPackage = CodedDescription("SPC", "C"),
    phase = CodedDescription("STD", "Standard supervision"),
    recallStatus = null,
  )
}

/**
 * Stub data provider that uses the supplied CRN to generate data in the following way
 * - X001122 -> "001122" will become part of the offender's surname and contact info
 * - X001122 -> "00" will become part of the practitioner's surname and contact info
 * - X001122 -> "11" will become part of the practitioner's local admin, probation delivery and provider code
 * - X001122 -> First & last character "X2" will become the v2 tier score
 * - X001122 -> Last character will decide the v3 tier score: "0"-"6" become "A"-"G", "7" NOT_SUPERVISED,
 *   "8" MISSING, "9" "D". Last character "0" also makes that v3 tier provisional.
 * - X001122 -> Last character will decide the risk level "2" will become "MEDIUM"
 * - X001122 -> Last character will decide the supervision package phase: "1" early engagement,
 *   "2" final third, "3" recalled and back in custody, "4" no active package, "6" an open recall
 *   request, "7" recalled and unlawfully at large, anything else standard supervision
 */
class GeneratingStubDataProvider : StubDataProvider {
  override fun provideCase(crn: CRN): ContactDetails {
    val parsed = parseCrn(crn)
    return ContactDetails(
      crn = crn,
      name = Name(
        forename = "Person",
        surname = "Number${parsed.person}",
      ),
      dateOfBirth = LocalDate.of(1980, 1, 1),
      mobile = "0770${parsed.person.padStart(4, '0')}",
      email = "person.number${parsed.person}@example.com",
      practitioner = PractitionerDetails(
        code = "N${parsed.practitioner}A${parsed.unit}",
        name = Name(
          forename = "Practitioner",
          surname = "Number${parsed.practitioner}",
        ),
        email = "practitioner.number${parsed.practitioner}@justice.gov.uk",
        localAdminUnit = OrganizationalUnit(
          code = "LAU${parsed.unit.padStart(3, '0')}",
          description = "Local Admin Unit ${parsed.unit}",
        ),
        probationDeliveryUnit = OrganizationalUnit(
          code = "PDU${parsed.unit.padStart(3, '0')}",
          description = "Probation Delivery Unit ${parsed.unit}",
        ),
        provider = OrganizationalUnit(
          code = "PRV${parsed.unit.padStart(3, '0')}",
          description = "Provider ${parsed.unit}",
        ),
        unallocated = false,
        username = "practitioner.number${parsed.practitioner}",
      ),
      events = listOf(
        Event(
          1,
          CodedDescription("0001", "stealing candy"),
          Event.Sentence(
            LocalDate.now(ZoneId.of("Europe/London")).minusWeeks(90),
            "Sentence description here",
          ),
        ),
      ),
    )
  }

  override fun provideTierDetails(crn: String, version: TierApiVersion): TierDetails = TierDetails(
    tierScore = when (version) {
      TierApiVersion.V2 -> "${crn.substring(0)}${crn.substring(5)}"
      TierApiVersion.V3 -> V3_SCORES_BY_LAST_DIGIT[crn.last().digitToInt()]
    },
    calculationId = UUID.randomUUID(),
    calculationDate = LocalDate.of(2026, 1, 1),
    changeReason = "A registration was added",
    provisional = if (version == TierApiVersion.V3) crn.last() == '0' else null,
  )

  override fun provideArnsWidget(crn: CRN): ArnsWidget {
    val risk = when (crn.substring(5)) {
      "1" -> "VER_LOW"
      "2" -> "LOW"
      "3" -> "MEDIUM"
      "4" -> "HIGH"
      else -> "VERY_HIGH"
    }
    return ArnsWidget(
      overallRisk = risk,
      assessedOn = LocalDate.of(2026, 1, 1),
      riskInCommunity = RiskInSituation(
        public = risk,
        children = "LOW",
        knownAdult = "MEDIUM",
        staff = "VERY_HIGH",
        prisoners = null,
      ),
      riskInCustody = RiskInSituation(
        public = risk,
        children = "LOW",
        knownAdult = "MEDIUM",
        staff = "VERY_HIGH",
        prisoners = "VERY_HIGH",
      ),
    )
  }

  override fun provideSupervisionPackageDetails(crn: CRN): SupervisionPackageDetails {
    val packageC = CodedDescription("SPC", "C")
    return when (crn.last()) {
      '1' -> SupervisionPackageDetails(packageC, CodedDescription("INIT", "Early engagement"), recallStatus = null)
      '2' -> SupervisionPackageDetails(packageC, CodedDescription("FTHRD", "Final third"), recallStatus = null)
      // Released, then recalled, and back in prison. C "Recalled" is confirmed by Manage People on
      // Probation; the prison is from Supervision Packages' own test data.
      '3' -> SupervisionPackageDetails(
        packageC,
        CodedDescription("SENT", "In Custody"),
        // The recall has been decided, which end-dates the request NSI, so no recall status remains.
        recallStatus = null,
        custody = listOf(
          CustodyDetails(
            eventNumber = "1",
            status = CodedDescription("C", "Recalled"),
            location = CodedDescription("SWIHMP", "Swansea (HMP)"),
            latestReleaseDate = LocalDate.of(2026, 1, 12),
            latestRecallDate = LocalDate.of(2026, 3, 2),
          ),
        ),
      )
      '4' -> SupervisionPackageDetails(supervisionPackage = null, phase = null, recallStatus = null)
      // An undecided recall request, like Y058556 on dev: REC01 is a real r_nsi_status for the REC
      // ("Request for Recall") NSI type. Nothing is recalled yet, so there is no custody record.
      '6' -> SupervisionPackageDetails(
        CodedDescription("SPNK", "Not Yet Known"),
        CodedDescription("SPNK", "Not Yet Known"),
        CodedDescription("REC01", "Recall Initiated"),
      )
      // Recalled but not returned to custody. UATLRG is the location Manage People on Probation checks
      // for "unlawfully at large". It does not count as recalled. Unlawfully at large is not in custody, so the phase is not SENT; what
      // Supervision Packages reports for it is not confirmed, so it is left as not yet known.
      '7' -> SupervisionPackageDetails(
        packageC,
        CodedDescription("SPNK", "Not Yet Known"),
        recallStatus = null,
        custody = listOf(
          CustodyDetails(
            eventNumber = "1",
            status = CodedDescription("C", "Recalled"),
            location = CodedDescription("UATLRG", "Unlawfully at Large"),
            latestReleaseDate = LocalDate.of(2026, 1, 12),
            latestRecallDate = LocalDate.of(2026, 3, 2),
          ),
        ),
      )
      else -> SupervisionPackageDetails(packageC, CodedDescription("STD", "Standard supervision"), recallStatus = null)
    }
  }

  private data class CrnIds(
    val person: String,
    val practitioner: String,
    val unit: String,
  )

  private fun parseCrn(crn: CRN): CrnIds {
    assert(crn.matches(Regex("[A-Z][0-9]{6}"))) { "Invalid CRN supplied: $crn" }
    return CrnIds(crn.substring(1), crn.substring(1, 3), crn.substring(3, 5))
  }

  private companion object {
    val V3_SCORES_BY_LAST_DIGIT = listOf("A", "B", "C", "D", "E", "F", "G", TierDetails.NOT_SUPERVISED, TierDetails.MISSING, "D")
  }
}
