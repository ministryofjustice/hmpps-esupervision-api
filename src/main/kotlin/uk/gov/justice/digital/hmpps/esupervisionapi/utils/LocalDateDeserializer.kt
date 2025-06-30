package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate

class LocalDateDeserializer : JsonDeserializer<LocalDate>() {

  private fun extractNumbers(dateString: String): List<Int> {
    val regex = "\\d+".toRegex()
    return regex.findAll(dateString).map { it.value.trimStart { it == '0' }.toInt() }.toList()
  }

  override fun deserialize(p: com.fasterxml.jackson.core.JsonParser, ctxt: DeserializationContext): LocalDate {
    val node: JsonNode = p.codec.readTree(p)
    val dateString = node.asText()
    val numbers = extractNumbers(dateString)
    if (numbers.size != 3) {
      throw RuntimeException("Expected 3 numbers, got ${numbers.size}, input='$dateString'")
    }
    return LocalDate.of(numbers[0], numbers[1], numbers[2])
  }
}
