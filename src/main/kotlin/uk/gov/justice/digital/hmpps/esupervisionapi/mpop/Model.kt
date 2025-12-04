package uk.gov.justice.digital.hmpps.esupervisionapi.mpop

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseDto(
  val crn: String,
  val name: Name,
  val mobile: String? = null,
  val email: String? = null,
  val practitioner: Practitioner? = null,
)

data class Name(
  val forename: String,
  val surname: String,
)

data class Practitioner(
  val name: Name,
  val email: String? = null,
  val localAdminUnit: UnitInfo? = null,
  val probationDeliveryUnit: UnitInfo? = null,
  val provider: UnitInfo? = null,
)

data class UnitInfo(
  val code: String,
  val description: String,
)
