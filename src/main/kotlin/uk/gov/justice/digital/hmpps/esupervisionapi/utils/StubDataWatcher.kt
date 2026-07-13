package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.StubDataWatcher.Companion.LOG
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.StubDataWatcher.DataConfig
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isDirectory

interface ICrnSet {
  var allowedCrns: Set<String>
}

interface IWatcher {
  fun startWatchingChanges(): Boolean
  fun stopWatchingChanges()
}

class ConstantCrnSet(override var allowedCrns: Set<String>) : ICrnSet {
  companion object {
    fun fromResource(resource: String): ConstantCrnSet {
      val path = Paths.get(resource)
      val objectMapper = jacksonObjectMapper().registerKotlinModule()
      return ConstantCrnSet(extractData(objectMapper, path))
    }
  }
}

/**
 * A helper class meant to make it easier to run the app without relying on external services.
 * The file specified by `path` will be observed for changes and its contents will re-populate `allowedCrns`.
 *
 */
class StubDataWatcher(val path: Path) :
  ICrnSet,
  IWatcher {

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class DataConfig(
    /** The CRNs we want to accept/handle in our service */
    val crns: Set<String> = emptySet(),
  )

  data class WatchInfo(
    val service: WatchService,
    val task: Thread,
  )

  val watchInfo = AtomicReference<WatchInfo?>(null)

  @Volatile
  override var allowedCrns: Set<String> = emptySet()

  @Volatile
  var keepWatchingChanges = true

  private val objectMapper = jacksonObjectMapper().registerKotlinModule()

  init {
    if (path.isDirectory()) {
      throw IllegalArgumentException("path needs to be a file, not a directory: $path")
    }
  }

  /**
   * @return whether the watcher was started successfully
   */
  override fun startWatchingChanges(): Boolean {
    if (watchInfo.get() != null) {
      throw IllegalStateException("Already watching changes")
    }

    val service = FileSystems.getDefault().newWatchService()
    val parentDir = path.parent
    parentDir.register(service, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)

    val thread = Thread({
      while (keepWatchingChanges) {
        val key = try {
          service.take()
        } catch (ex: ClosedWatchServiceException) {
          LOG.info("WatchService closed, exiting watcher thread for {}", path)
          break
        } catch (ex: InterruptedException) {
          LOG.info("Watcher thread interrupted, exiting for {}", path)
          break
        } catch (ex: Exception) {
          LOG.error("Unexpected exception waiting for watch key: {}", ex.message)
          break
        }

        try {
          key.pollEvents().forEach { event ->
            try {
              val changedRelative = event.context() as? Path ?: return@forEach
              val changedAbsolute = parentDir.resolve(changedRelative).normalize()
              if (changedAbsolute == path.normalize()) {
                LOG.info("Detected change for watched file: {}", path)
                extractData(objectMapper, path)
              } else {
                LOG.debug("File changed but not the watched file: {}", changedAbsolute)
              }
            } catch (ex: Exception) {
              LOG.error("Error handling watch event: {}", ex.message)
            }
          }
        } finally {
          try {
            key.reset()
          } catch (ex: Exception) {
            LOG.debug("Failed to reset watch key: {}", ex.message)
          }
        }
      }
    }, "ndilius-watcher-${path.fileName}")
    thread.isDaemon = true

    val result = watchInfo.compareAndSet(null, WatchInfo(service, thread))
    if (result) {
      extractData(objectMapper, path)
      thread.start()
    } else {
      service.close()
    }

    return result
  }

  override fun stopWatchingChanges() {
    keepWatchingChanges = false
    val current = watchInfo.get()
    if (current != null) {
      if (watchInfo.compareAndSet(current, null)) {
        current.service.close()
      }
    }
  }

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}

private fun extractData(objectMapper: ObjectMapper, path: Path): Set<CRN> {
  try {
    val wrapper = objectMapper.readValue<DataConfig>(path.toFile())
    // convert path to resource
    val res = Paths.get("")
    LOG.info("Loaded {} CRNs from {}", wrapper.crns.size, path)
    return wrapper.crns
  } catch (ex: Exception) {
    LOG.error("Failed to parse personalDetails from {}: {}", path, ex.message)
  }
  return emptySet()
}
