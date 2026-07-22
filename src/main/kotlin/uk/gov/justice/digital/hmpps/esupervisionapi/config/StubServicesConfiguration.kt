package uk.gov.justice.digital.hmpps.esupervisionapi.config

import io.micrometer.core.annotation.Timed
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.GeneratingStubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.StubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.StubDataWatcher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PersonalDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.ArnsWidget
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.arns.IArnsApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.ITierApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierDetails
import java.nio.file.Path

@Configuration
class StubServicesConfiguration {

  @Bean
  @Profile("local & stubndilius")
  fun ndiliusApiClient(): INdiliusApiClient {
    LOG.info("Creating stubbed Ndilius API client")
    return StubNdiliusApiClient()
  }

  @Bean
  @Profile("local & stubtier")
  fun tierApiClient(): ITierApiClient {
    LOG.info("Creating stubbed Tier API client")
    return StubTierApiClient()
  }

  @Bean
  @Profile("local & stubarns")
  fun arnsApiClient(): IArnsApiClient {
    LOG.info("Creating stubbed Arns API client")
    return StubArnsApiClient()
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
open class StubNdiliusApiClient(
  val watcher: StubDataWatcher = StubDataWatcher(Path.of("src/test/resources/ndelius-responses/default.json")),
  val dataProvider: StubDataProvider = GeneratingStubDataProvider(),
) : INdiliusApiClient,
  DisposableBean {

  init {
    watcher.startWatchingChanges()
  }

  override fun destroy() {
    watcher.stopWatchingChanges()
  }

  override fun validatePersonalDetails(personalDetails: PersonalDetails): Boolean {
    LOG.debug("Validating personal details: {}", personalDetails)
    return watcher.allowedCrns.contains(personalDetails.crn)
  }

  @Timed("ndelius.get-contact-details", extraTags = ["method", "GET", "endpoint", "/case/{crn}"], description = "Time taken to get contact details (STUB)")
  override fun getContactDetails(crn: String): ContactDetails? {
    LOG.debug("Fetching contact details for CRN: {}", crn)
    if (watcher.allowedCrns.contains(crn)) {
      return dataProvider.provideCase(crn)
    }
    LOG.debug("CRN {} not found in allowed list", crn)
    return null
  }

  override fun getContactDetailsForMultiple(crns: List<String>): List<ContactDetails> {
    LOG.debug("Fetching contact details for {} CRNs, starting with {}", crns.size, crns.take(4))
    val incomingCrns = HashSet<String>(crns)
    val allowedCrns = watcher.allowedCrns
    if (allowedCrns.containsAll(incomingCrns)) {
      return crns.map { dataProvider.provideCase(it) }
    }
    LOG.debug("Not all CRNs found in allowed list: {}", incomingCrns.subtract(allowedCrns))
    return emptyList()
  }

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}

open class StubTierApiClient(
  val watcher: StubDataWatcher = StubDataWatcher(Path.of("src/test/resources/tier-api-responses/default.json")),
  val dataProvider: StubDataProvider = GeneratingStubDataProvider(),
) : ITierApiClient,
  DisposableBean {

  init {
    watcher.startWatchingChanges()
  }

  override fun destroy() {
    watcher.stopWatchingChanges()
  }

  override fun getTierDetails(crn: String): TierDetails? {
    LOG.debug("Fetching tier details for CRN: {}", crn)
    if (watcher.allowedCrns.contains(crn)) {
      return dataProvider.provideTierDetails(crn)
    }
    LOG.debug("CRN {} not found in allowed list", crn)
    return null
  }

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}

open class StubArnsApiClient(
  val watcher: StubDataWatcher = StubDataWatcher(Path.of("src/test/resources/arns-api-responses/default.json")),
  val dataProvider: StubDataProvider = GeneratingStubDataProvider(),
) : IArnsApiClient,
  DisposableBean {

  init {
    watcher.startWatchingChanges()
  }

  override fun destroy() {
    watcher.stopWatchingChanges()
  }

  override fun getRiskWidget(crn: String): ArnsWidget? {
    LOG.debug("Fetching tier details for CRN: {}", crn)
    if (watcher.allowedCrns.contains(crn)) {
      return dataProvider.provideArnsWidget(crn)
    }
    LOG.debug("CRN {} not found in allowed list", crn)
    return null
  }

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}
