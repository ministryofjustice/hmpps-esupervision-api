package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInvite

@Service
class S3UploadService(
  val s3uploadClient: S3Client,
  @Value("\${aws.s3.image-uploads}") private val imageUploadBucket: String,
) {

  /**
   * Returns the uploaded object key on successful upload.
   */
  fun uploadInvitePhoto(invite: OffenderInvite, image: MultipartFile): String {
    val ext = image.originalFilename?.substringAfterLast('.', "") ?: ""
    val key = "invite-${invite.uuid}.$ext"

    val request = PutObjectRequest.builder()
      .bucket(imageUploadBucket)
      .key(key)
      .contentType(image.contentType)
      .contentLength(image.size.toLong())
      .build()

    s3uploadClient.putObject(
      request,
      RequestBody.fromInputStream(image.inputStream, image.size),
    )

    return key
  }
}
