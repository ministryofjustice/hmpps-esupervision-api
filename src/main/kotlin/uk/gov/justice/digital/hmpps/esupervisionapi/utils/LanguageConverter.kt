package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Language

@Component
class LanguageConverter : Converter<String, Language> {
  override fun convert(source: String): Language? = Language.fromString(source)
}
