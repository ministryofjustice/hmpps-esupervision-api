package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ConstantCrnSet
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.GeneratingStubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.ICrnSet
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.IWatcher
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.StubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.StubDataWatcher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PersonalDetails
import java.nio.file.Path

@Configuration
class StubServicesConfiguration {

  @Bean
  @Profile("stubndilius")
  fun ndiliusApiClient(latency: StubLatencyProperties): INdiliusApiClient {
    LOG.info("Creating stubbed NDelius API client (latency injection enabled={})", latency.enabled)
    val appEnv = System.getenv("APP_ENV")?.trim()
    val crns: ICrnSet = if (appEnv.isNullOrEmpty()) {
      StubDataWatcher(Path.of("src/test/resources/ndelius-responses/default.json"))
    } else {
      ConstantCrnSet.fromResource("ndelius-responses/${appEnv.lowercase()}.json")
    }
    return StubNdiliusApiClient(latency = latency, watcher = crns)
  }

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}

/**
 * This stub client takes CRNs from the file observed by StubDataWatcher and returns
 * generated data if given CRN is found in the file.
 *
 * The file can be edited at runtime and will be automatically reloaded.
 */
class StubNdiliusApiClient(
  val watcher: ICrnSet = StubDataWatcher(Path.of("src/test/resources/ndelius-responses/default.json")),
  val dataProvider: StubDataProvider = GeneratingStubDataProvider(),
  private val latency: StubLatencyProperties = StubLatencyProperties(),
) : INdiliusApiClient,
  DisposableBean {

  init {
    if (watcher is IWatcher) {
      watcher.startWatchingChanges()
    }
  }

  override fun destroy() {
    if (watcher is IWatcher) {
      watcher.stopWatchingChanges()
    }
  }

  override fun validatePersonalDetails(personalDetails: PersonalDetails): Boolean {
    LOG.debug("Validating personal details: {}", personalDetails)
    latency.sleep(latency.ndiliusValidate)
    return watcher.allowedCrns.contains(personalDetails.crn)
  }

  override fun getContactDetails(crn: String): ContactDetails? {
    LOG.debug("Fetching contact details for CRN: {}", crn)
    latency.sleep(latency.ndiliusContact)
    if (watcher.allowedCrns.contains(crn)) {
      return dataProvider.provideCase(crn)
    }
    LOG.debug("CRN {} not found in allowed list", crn)
    return null
  }

  override fun getContactDetailsForMultiple(crns: List<String>): List<ContactDetails> {
    LOG.debug("Fetching contact details for {} CRNs, starting with {}", crns.size, crns.take(4))
    latency.sleep(latency.ndiliusContact)
    val incomingCrns = HashSet<String>(crns)
    val allowedCrns = watcher.allowedCrns
    val notAllowed = incomingCrns.subtract(allowedCrns)
    if (notAllowed.isNotEmpty()) {
      LOG.debug("Following CRNs not found in allowed list: {}", notAllowed)
    }
    return crns.map { dataProvider.provideCase(it) }
  }

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}
