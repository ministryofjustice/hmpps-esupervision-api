package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OrganizationalUnit
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.ArnsWidget
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.RiskInSituation
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierDetails
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

typealias CRN = String

interface StubDataProvider {
  fun provideCase(crn: CRN): ContactDetails
  fun provideTierDetails(crn: CRN): TierDetails
  fun provideArnsWidget(crn: CRN): ArnsWidget
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
    ),
  )

  override fun provideTierDetails(crn: String): TierDetails = TierDetails(
    tierScore = "D2",
    calculationId = UUID.randomUUID(),
    calculationDate = LocalDate.of(2026, 1, 1),
    changeReason = "A registration was added",
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
}

/**
 * Stub data provider that uses the supplied CRN to generate data in the following way
 * - X001122 -> "001122" will become part of the offender's surname and contact info
 * - X001122 -> "00" will become part of the practitioner's surname and contact info
 * - X001122 -> "11" will become part of the practitioner's local admin, probation delivery and provider code
 * - X001122 -> First & last character "X2" will become the tier score
 * - X001122 -> Last character will decide the risk level "2" will become "MEDIUM"
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

  override fun provideTierDetails(crn: String): TierDetails = TierDetails(
    tierScore = "${crn.substring(0)}${crn.substring(5)}",
    calculationId = UUID.randomUUID(),
    calculationDate = LocalDate.of(2026, 1, 1),
    changeReason = "A registration was added",
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

  private data class CrnIds(
    val person: String,
    val practitioner: String,
    val unit: String,
  )

  private fun parseCrn(crn: CRN): CrnIds {
    assert(crn.matches(Regex("[A-Z][0-9]{6}"))) { "Invalid CRN supplied: $crn" }
    return CrnIds(crn.substring(1), crn.substring(1, 3), crn.substring(3, 5))
  }
}
