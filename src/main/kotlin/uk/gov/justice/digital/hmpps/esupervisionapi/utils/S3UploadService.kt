package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInvite
import java.net.URL
import java.time.Duration
import java.util.UUID

/**
 *
 */
data class InvitePhotoKey(
  val invite: UUID,
) {
  fun asString(): String {
    return "invite-${invite}"
  }
}

@Service
class S3UploadService(
  val s3uploadClient: S3Client,
  val s3Presigner: S3Presigner,
  @Value("\${aws.s3.image-uploads}") private val imageUploadBucket: String,
) {

  internal fun putObjectRequest(invite: OffenderInvite, contentType: String): PutObjectRequest {
    val request = PutObjectRequest.builder()
      .bucket(imageUploadBucket)
      .key(InvitePhotoKey(invite.uuid).asString())
      .contentType(contentType)
      .build()
    return request
  }

  /**
   * Returns the uploaded object key on successful upload.
   */
  fun uploadInvitePhoto(invite: OffenderInvite, image: MultipartFile): String {
    if (image.contentType != null) {
      val request = putObjectRequest(invite, image.contentType!!)
      s3uploadClient.putObject(
        request,
        RequestBody.fromInputStream(image.inputStream, image.size),
      )
      return request.key()
    }
    throw RuntimeException("file content type is null for file originalFileName='${image.originalFilename}'")

  }

  /**
   * Generates a pre-signed URL for uploading a file to S3.
   * @param invite The offender invite.
   * @param contentType content type of the file - will be used to get file extension
   * @param duration The expiration time for the pre-signed URL in minutes.
   * @return A pre-signed URL for uploading the file.
   */
  fun generatePresignedUploadUrl(
    invite: OffenderInvite,
    contentType: String = "application/octet-stream",
    duration: Duration
  ): URL {
    val putRequest = putObjectRequest(invite, contentType)
    val presignRequest = PutObjectPresignRequest.builder()
      .putObjectRequest(putRequest)
      .signatureDuration(duration)
      .build()

    return s3Presigner.presignPutObject(presignRequest).url()
  }

  fun isInvitePhotoUploaded(invite: OffenderInvite): Boolean {
    val request = HeadObjectRequest.builder()
      .bucket(imageUploadBucket)
      .key(InvitePhotoKey(invite.uuid).asString())
      .build()

    try {
      s3uploadClient.headObject(request)
      return true
    } catch (e: NoSuchKeyException) {
      return false
    }
  }

}
