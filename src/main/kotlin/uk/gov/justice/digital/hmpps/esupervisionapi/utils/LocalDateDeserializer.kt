package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer
import java.time.LocalDate

class LocalDateDeserializer : ValueDeserializer<LocalDate>() {

  private fun extractNumbers(dateString: String): List<Int> {
    val regex = "\\d+".toRegex()
    return regex.findAll(dateString).map { it.value.trimStart { it == '0' }.toInt() }.toList()
  }

  override fun deserialize(p: tools.jackson.core.JsonParser, ctxt: DeserializationContext): LocalDate {
    val node: JsonNode = ctxt.readTree(p)
    val dateString = node.asText()
    val numbers = extractNumbers(dateString)
    if (numbers.size != 3) {
      throw RuntimeException("Expected 3 numbers, got ${numbers.size}, input='$dateString'")
    }
    return LocalDate.of(numbers[0], numbers[1], numbers[2])
  }
}
