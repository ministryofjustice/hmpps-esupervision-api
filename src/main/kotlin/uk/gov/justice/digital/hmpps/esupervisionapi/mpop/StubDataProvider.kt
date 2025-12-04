package uk.gov.justice.digital.hmpps.esupervisionapi.mpop

interface StubDataProvider {
  fun provideCase(crn: CRN): CaseDto
}

class DefaultStubDataProvider : StubDataProvider {
  override fun provideCase(crn: CRN): CaseDto = CaseDto(
    crn = crn,
    name = Name(
      forename = "John",
      surname = "Smith",
    ),
    mobile = "07700900123",
    email = "john.smith@example.com",
    practitioner = Practitioner(
      name = Name(
        forename = "Sarah",
        surname = "Johnson",
      ),
      email = "sarah.johnson@justice.gov.uk",
      localAdminUnit = UnitInfo(
        code = "LAU001",
        description = "London Central Local Admin Unit",
      ),
      probationDeliveryUnit = UnitInfo(
        code = "PDU001",
        description = "London Probation Delivery Unit",
      ),
      provider = UnitInfo(
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
class MultiSampleStubDataProvider : StubDataProvider {
  override fun provideCase(crn: CRN): CaseDto {
    val parsed = parseCrn(crn)
    return CaseDto(
      crn = crn,
      name = Name(
        forename = "Person",
        surname = "Number${parsed.person}",
      ),
      mobile = "0770090${parsed.person.padStart(4, '0')}",
      email = "person.number${parsed.person}@example.com",
      practitioner = Practitioner(
        name = Name(
          forename = "Practitioner",
          surname = "Number${parsed.practitioner}",
        ),
        email = "practitioner.number${parsed.practitioner}@justice.gov.uk",
        localAdminUnit = UnitInfo(
          code = "LAU${parsed.unit.padStart(3, '0')}",
          description = "Local Admin Unit ${parsed.unit}",
        ),
        probationDeliveryUnit = UnitInfo(
          code = "PDU${parsed.unit.padStart(3, '0')}",
          description = "Probation Delivery Unit ${parsed.unit}",
        ),
        provider = UnitInfo(
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
