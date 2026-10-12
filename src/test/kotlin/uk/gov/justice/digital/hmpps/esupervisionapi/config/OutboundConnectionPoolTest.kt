package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the fix for stale keep-alive connections (error(-104) Connection reset by peer): the pool
 * must close connections itself once idle, rather than holding them until the upstream does.
 */
class OutboundConnectionPoolTest {

  // doOnConnection fires per request on a keep-alive connection, so count distinct sockets.
  private val connectionsOpened = ConcurrentHashMap.newKeySet<String>()
  private val connectionClosed = CountDownLatch(1)
  private lateinit var server: DisposableServer

  @BeforeEach
  fun startServer() {
    server = HttpServer.create()
      .port(0)
      .doOnConnection { conn ->
        connectionsOpened.add(conn.channel().id().asLongText())
        conn.onDispose { connectionClosed.countDown() }
      }
      .handle { _, res -> res.sendString(Mono.just("ok")) }
      .bindNow()
  }

  @AfterEach
  fun stopServer() {
    server.disposeNow()
  }

  @Test
  fun `the pooled client is backed by the supplied connection provider`() {
    val provider = ConnectionProvider.create("test-pool")
    try {
      val httpClient = WebClientConfiguration.pooledHttpClient(provider, Duration.ofSeconds(5))

      assertThat(httpClient.configuration().connectionProvider()).isSameAs(provider)
      assertThat(httpClient.configuration().responseTimeout()).isEqualTo(Duration.ofSeconds(5))
    } finally {
      provider.disposeLater().block()
    }
  }

  @Test
  fun `idle connections are evicted in the background without waiting for the next borrow`() {
    val provider = ConnectionProvider.builder("test-pool")
      .maxIdleTime(Duration.ofMillis(200))
      .evictInBackground(Duration.ofMillis(100))
      .build()
    try {
      val webClient = webClient(provider)

      assertThat(get(webClient)).isEqualTo("ok")
      assertThat(connectionClosed.count).describedAs("connection kept alive after the request").isEqualTo(1)

      assertThat(connectionClosed.await(5, TimeUnit.SECONDS))
        .describedAs("idle pooled connection should be closed by the client")
        .isTrue()

      assertThat(get(webClient)).isEqualTo("ok")
      assertThat(connectionsOpened.size).describedAs("next call opens a fresh connection").isEqualTo(2)
    } finally {
      provider.disposeLater().block()
    }
  }

  @Test
  fun `connections within the idle window are reused`() {
    val provider = ConnectionProvider.builder("test-pool")
      .maxIdleTime(Duration.ofSeconds(20))
      .evictInBackground(Duration.ofSeconds(10))
      .build()
    try {
      val webClient = webClient(provider)

      get(webClient)
      get(webClient)

      assertThat(connectionsOpened.size).isEqualTo(1)
    } finally {
      provider.disposeLater().block()
    }
  }

  private fun webClient(provider: ConnectionProvider) = WebClient.builder()
    .baseUrl("http://localhost:${server.port()}")
    .clientConnector(ReactorClientHttpConnector(WebClientConfiguration.pooledHttpClient(provider, Duration.ofSeconds(5))))
    .build()

  private fun get(webClient: WebClient): String? = webClient.get().uri("/").retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(5))
}
