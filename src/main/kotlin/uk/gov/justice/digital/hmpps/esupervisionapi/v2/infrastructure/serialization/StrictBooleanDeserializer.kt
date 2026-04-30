import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer

class StrictBooleanDeserializer : JsonDeserializer<Boolean>() {

  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Boolean {
    val token = p.currentToken

    if (token == JsonToken.VALUE_TRUE) return true
    if (token == JsonToken.VALUE_FALSE) return false
    throw RuntimeException("Expected strict boolean (true/false), but got invalid value")
  }

  override fun getNullValue(ctxt: DeserializationContext): Boolean = throw RuntimeException("Expected strict boolean (true/false), but got null")
}
