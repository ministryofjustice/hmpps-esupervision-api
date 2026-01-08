package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OrganizationalUnit
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails

typealias CRN = String

interface StubDataProvider {
  fun provideCase(crn: CRN): ContactDetails
}

class DefaultStubDataProvider : StubDataProvider {
  override fun provideCase(crn: CRN): ContactDetails = ContactDetails(
    crn = crn,
    name = Name(
      forename = "John",
      surname = "Smith",
    ),
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
}

/**
 * Stub data provider that uses the supplied CRN to generate data in the following way
 * - X001122 -> "001122" will become part of the offender's surname and contact info
 * - X001122 -> "00" will become part of the practitioner's surname and contact info
 * - X001122 -> "11" will become part of the practitioner's local admin, probation delivery and provider code
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
      mobile = "0770090${parsed.person.padStart(4, '0')}",
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
