import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

class StrictBooleanDeserializer : ValueDeserializer<Boolean>() {

  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Boolean {
    val token = p.currentToken()

    if (token == JsonToken.VALUE_TRUE) return true
    if (token == JsonToken.VALUE_FALSE) return false
    throw RuntimeException("Expected strict boolean (true/false), but got invalid value")
  }

  override fun getNullValue(ctxt: DeserializationContext): Boolean = throw RuntimeException("Expected strict boolean (true/false), but got null")
}
