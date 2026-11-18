package uk.gov.justice.digital.hmpps.esupervisionapi.photos

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.util.spi.ToolProvider

class OffenderPhotoBoundaryTest {
  @Test
  fun `production dependencies respect the photo client boundary`() {
    val classes = Path.of(OffenderPhotoClient::class.java.protectionDomain.codeSource.location.toURI())
    val output = StringWriter()
    val writer = PrintWriter(output)
    val jdeps = ToolProvider.findFirst("jdeps").orElseThrow()
    val exitCode = jdeps.run(writer, writer, "-verbose:class", "-filter:none", "--ignore-missing-deps", classes.toString())
    assertThat(exitCode).withFailMessage(output.toString()).isZero()

    val edges = output.toString().lineSequence().mapNotNull { line ->
      dependency.matchEntire(line)?.destructured?.let { (from, to) -> from to to }
    }.toList()
    assertThat(edges).anyMatch { (from, to) -> from == "$PHOTOS.s3.S3OffenderPhotoClient" && to == "$PHOTOS.OffenderPhotoClient" }
    assertThat(edges).anyMatch { (from, to) -> from == "$APP.v2.CheckinService" && to == "$PHOTOS.OffenderPhotoClient" }

    val violations = edges.filter { (from, to) ->
      when {
        from.substringBeforeLast('.') == PHOTOS ->
          to.substringBeforeLast('.') != PHOTOS && !to.startsWith("java.") && !to.startsWith("kotlin.") && to != "org.jetbrains.annotations.NotNull"
        from.startsWith("$PHOTOS.") && to.startsWith("$APP.") ->
          !to.startsWith("$PHOTOS.") && to.substringBefore('$') !in sharedStorage
        !from.startsWith("$PHOTOS.") && to.startsWith("$PHOTOS.") ->
          to.substringBeforeLast('.') != PHOTOS
        else -> false
      }
    }
    assertThat(violations).isEmpty()
  }

  companion object {
    private const val APP = "uk.gov.justice.digital.hmpps.esupervisionapi"
    private const val PHOTOS = "$APP.photos"
    private val sharedStorage = setOf("PresignedUpload", "S3PresigningKt", "S3VersionPurger", "S3PurgeResult")
      .map { "$APP.v2.infrastructure.storage.$it" }.toSet()
    private val dependency = Regex("""\s+(\S+)\s+->\s+(\S+)\s+.*""")
  }
}
