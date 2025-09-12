package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait

class TestContainersSessionListener : LauncherSessionListener {
  override fun launcherSessionOpened(session: LauncherSession?) {
    postgres.start()
  }

  override fun launcherSessionClosed(session: LauncherSession?) {
    postgres.stop()
  }

  companion object {
    val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer<Nothing>("postgres:17.5").apply {
      withNetworkAliases("postgres-test")
      withDatabaseName("testcontainers")
      withUsername("postgres")
      withExposedPorts(5432)
      setWaitStrategy(Wait.forListeningPort())
    }
  }
}
