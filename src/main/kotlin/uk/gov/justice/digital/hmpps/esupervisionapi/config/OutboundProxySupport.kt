package uk.gov.justice.digital.hmpps.esupervisionapi.config

import reactor.netty.http.client.HttpClient
import reactor.netty.transport.ProxyProvider
import java.net.URI
import java.util.Properties

/*
 * Port of hmpps-kotlin's ProxySupport.kt (2.5.0), which is `internal` to the library. We need it
 * because we build our own HttpClient (to supply a ConnectionProvider) instead of the library's
 * `proxyAwareHttpClient`, and must keep honouring HTTPS_PROXY / HTTP_PROXY / NO_PROXY and the JVM
 * proxy system properties exactly as before. Drop this if the library ever accepts a provider.
 */

private const val DEFAULT_PROXY_PORT: Int = 3128
private val regexMetaCharacters = setOf('\\', '.', '^', '$', '+', '?', '(', ')', '[', ']', '{', '}', '|')

internal data class ProxyConfiguration(
  val host: String,
  val port: Int,
  val nonProxyHostsPattern: String? = null,
)

internal fun HttpClient.withResolvedProxy(proxyConfiguration: ProxyConfiguration? = resolveProxyConfiguration()): HttpClient {
  if (proxyConfiguration == null) return this
  return proxy { proxy ->
    val builder = proxy
      .type(ProxyProvider.Proxy.HTTP)
      .host(proxyConfiguration.host)
      .port(proxyConfiguration.port)
    proxyConfiguration.nonProxyHostsPattern?.let(builder::nonProxyHosts)
  }
}

internal fun resolveProxyConfiguration(
  environment: Map<String, String> = System.getenv(),
  systemProperties: Properties = System.getProperties(),
): ProxyConfiguration? {
  val nonProxyHostsPattern =
    toReactorNoProxyHostsPattern(getEnvironmentValue(environment, "NO_PROXY"))
      ?: toReactorNonProxyHostsPattern(firstNonBlank(systemProperties.getProperty("https.nonProxyHosts"), systemProperties.getProperty("http.nonProxyHosts")))

  parseProxyConfigurationFromEnvironment(environment)?.let { proxyConfiguration ->
    return proxyConfiguration.copy(nonProxyHostsPattern = nonProxyHostsPattern)
  }

  val proxyHostPropertyName = when {
    !systemProperties.getProperty("https.proxyHost").isNullOrBlank() -> "https.proxyHost"
    !systemProperties.getProperty("http.proxyHost").isNullOrBlank() -> "http.proxyHost"
    else -> return null
  }
  val proxyHost = systemProperties.getProperty(proxyHostPropertyName)
  val proxyPort = parseSystemPropertyProxyPort(systemProperties, proxyHostPropertyName)

  return ProxyConfiguration(proxyHost, proxyPort, nonProxyHostsPattern)
}

private fun toReactorNonProxyHostsPattern(nonProxyHosts: String?): String? = toReactorNonProxyHostsPattern(nonProxyHosts, '|') {
  it.toReactorRegexFragment()
}

private fun toReactorNoProxyHostsPattern(noProxyHosts: String?): String? = toReactorNonProxyHostsPattern(noProxyHosts, ',') {
  if (it.startsWith('.')) "*$it".toReactorRegexFragment() else it.toReactorRegexFragment()
}

private fun parseSystemPropertyProxyPort(systemProperties: Properties, proxyHostPropertyName: String): Int {
  val proxyPortPropertyName = proxyHostPropertyName.replace("Host", "Port")
  val proxyPortPropertyValue = systemProperties.getProperty(proxyPortPropertyName)

  if (proxyPortPropertyValue.isNullOrBlank()) return DEFAULT_PROXY_PORT

  return proxyPortPropertyValue.toIntOrNull()
    ?: throw IllegalArgumentException("Invalid proxy port '$proxyPortPropertyValue' configured for system property '$proxyPortPropertyName'")
}

private fun toReactorNonProxyHostsPattern(hosts: String?, separator: Char, regexFragmentBuilder: (String) -> String): String? {
  if (hosts.isNullOrBlank()) return null

  val patterns = hosts.split(separator)
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { "^${regexFragmentBuilder(it)}$" }

  return patterns.takeIf { it.isNotEmpty() }?.joinToString("|")
}

private fun parseProxyConfigurationFromEnvironment(environment: Map<String, String>): ProxyConfiguration? = firstNonBlank(
  getEnvironmentValue(environment, "HTTPS_PROXY"),
  getEnvironmentValue(environment, "HTTP_PROXY"),
)?.let(::parseProxyConfiguration)

private fun getEnvironmentValue(environment: Map<String, String>, key: String): String? = firstNonBlank(
  environment[key],
  environment[key.lowercase()],
  environment[key.uppercase()],
)
  ?: environment.entries
    .asSequence()
    .filter { it.key.equals(key, ignoreCase = true) }
    .sortedBy { it.key }
    .map { it.value }
    .firstOrNull { it.isNotBlank() }

private fun parseProxyConfiguration(proxyUrl: String): ProxyConfiguration {
  val normalizedProxyUrl = proxyUrl.takeIf { "://" in it } ?: "http://$proxyUrl"
  val uri = try {
    URI(normalizedProxyUrl)
  } catch (e: Exception) {
    throw IllegalArgumentException("Invalid proxy URL in environment configuration: '$proxyUrl'", e)
  }

  val host = uri.host
    ?: throw IllegalArgumentException("Invalid proxy URL in environment configuration: '$proxyUrl' does not contain a valid host")
  val port = if (uri.port > 0) uri.port else DEFAULT_PROXY_PORT

  return ProxyConfiguration(host, port)
}

private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

private fun String.toReactorRegexFragment(): String = buildString {
  this@toReactorRegexFragment.forEach { char ->
    when {
      char == '*' -> append(".*")
      char in regexMetaCharacters -> append('\\').append(char)
      else -> append(char)
    }
  }
}
