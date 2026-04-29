import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.exc.InvalidFormatException

class StrictBooleanDeserializer : JsonDeserializer<Boolean>() {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Boolean {
    val token = p.currentToken

    if (token == JsonToken.VALUE_TRUE) return true
    if (token == JsonToken.VALUE_FALSE) return false
    throw InvalidFormatException(
      p,
      "Strict boolean parsing failed. Expected literal true or false, but got: ${p.text}",
      p.text,
      Boolean::class.java,
    )
  }
  override fun getNullValue(ctxt: DeserializationContext): Boolean = throw InvalidFormatException(
    ctxt.parser,
    "The 'sensitive' field cannot be null. It must be strictly a boolean (true or false).",
    null,
    Boolean::class.java,
  )
}
