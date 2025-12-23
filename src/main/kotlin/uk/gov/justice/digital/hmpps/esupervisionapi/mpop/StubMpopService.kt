package uk.gov.justice.digital.hmpps.esupervisionapi.mpop

class StubMpopService(
  private val dataProvider: StubDataProvider = DefaultStubDataProvider(),
) : MpopService {

  override fun case(crn: CRN): CaseDto = dataProvider.provideCase(crn)

  override fun cases(crns: List<CRN>): List<CaseDto> = crns.map { dataProvider.provideCase(it) }
}
