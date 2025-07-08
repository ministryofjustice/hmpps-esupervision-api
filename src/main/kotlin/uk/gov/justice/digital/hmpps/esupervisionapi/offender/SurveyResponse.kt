package uk.gov.justice.digital.hmpps.esupervisionapi.offender

class SurveyResponse(
  val mentalHealth: String?,
  val assistance: List<String>,
  val mentalHealthSupport: String?,
  val alcoholSupport: String?,
  val drugsSupport: String?,
  val moneySupport: String?,
  val housingSupport: String?,
  val supportSystemSupport: String?,
  val otherSupport: String?,
  val callback: String?,
  val callbackDetails: String?,
) {
  fun dto(): SurveyResponseDto = SurveyResponseDto(
    mentalHealth = mentalHealth,
    assistance = assistance,
    mentalHealthSupport = mentalHealthSupport,
    alcoholSupport = alcoholSupport,
    drugsSupport = drugsSupport,
    moneySupport = moneySupport,
    housingSupport = housingSupport,
    supportSystemSupport = supportSystemSupport,
    otherSupport = otherSupport,
    callback = callback,
    callbackDetails = callbackDetails,
  )

  companion object {
    fun fromDto(dto: SurveyResponseDto): SurveyResponse = SurveyResponse(
      mentalHealth = dto.mentalHealth,
      assistance = dto.assistance,
      mentalHealthSupport = dto.mentalHealthSupport,
      alcoholSupport = dto.alcoholSupport,
      drugsSupport = dto.drugsSupport,
      moneySupport = dto.moneySupport,
      housingSupport = dto.housingSupport,
      supportSystemSupport = dto.supportSystemSupport,
      otherSupport = dto.otherSupport,
      callback = dto.callback,
      callbackDetails = dto.callbackDetails,
    )
  }
}
