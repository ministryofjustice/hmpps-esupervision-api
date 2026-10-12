package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.ProxyProvider
import java.net.InetSocketAddress
import java.util.Properties

class OutboundProxySupportTest {

  private fun props(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

  @Test
  fun `no proxy configured resolves to null`() {
    assertThat(resolveProxyConfiguration(emptyMap(), Properties())).isNull()
  }

  @Test
  fun `HTTPS_PROXY takes precedence over HTTP_PROXY and NO_PROXY becomes a host pattern`() {
    val config = resolveProxyConfiguration(
      mapOf("HTTP_PROXY" to "http://other:1", "https_proxy" to "http://proxy.local:8080", "NO_PROXY" to "localhost,.internal"),
      Properties(),
    )

    assertThat(config).isEqualTo(ProxyConfiguration("proxy.local", 8080, "^localhost$|^.*\\.internal$"))
  }

  @Test
  fun `environment proxy without scheme or port defaults to 3128`() {
    assertThat(resolveProxyConfiguration(mapOf("HTTPS_PROXY" to "proxy.local"), Properties()))
      .isEqualTo(ProxyConfiguration("proxy.local", 3128))
  }

  @Test
  fun `system properties are used when the environment has no proxy`() {
    val config = resolveProxyConfiguration(
      emptyMap(),
      props("https.proxyHost" to "sys.proxy", "https.proxyPort" to "9000", "http.nonProxyHosts" to "*.local|example.com"),
    )

    assertThat(config).isEqualTo(ProxyConfiguration("sys.proxy", 9000, "^.*\\.local$|^example\\.com$"))
  }

  @Test
  fun `an invalid system property port is rejected`() {
    assertThatThrownBy { resolveProxyConfiguration(emptyMap(), props("http.proxyHost" to "sys.proxy", "http.proxyPort" to "abc")) }
      .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `the resolved proxy is applied to the http client, honouring non-proxy hosts`() {
    val client = HttpClient.create().withResolvedProxy(ProxyConfiguration("proxy.local", 8080, "^localhost$"))
    val proxy = client.configuration().proxyProviderSupplier()!!.get()

    assertThat(proxy.type).isEqualTo(ProxyProvider.Proxy.HTTP)
    assertThat(proxy.shouldProxy(InetSocketAddress.createUnresolved("api.example.com", 443))).isTrue()
    assertThat(proxy.shouldProxy(InetSocketAddress.createUnresolved("localhost", 443))).isFalse()
  }

  @Test
  fun `no proxy leaves the http client unproxied`() {
    assertThat(HttpClient.create().withResolvedProxy(null).configuration().hasProxy()).isFalse()
  }
}
