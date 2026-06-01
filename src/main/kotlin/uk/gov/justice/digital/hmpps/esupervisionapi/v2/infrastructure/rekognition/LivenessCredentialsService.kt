package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

/**
 * Provides scoped temporary AWS credentials for the browser-based
 * FaceLivenessDetector component.
 */
interface LivenessCredentialsProvider {
  fun getCredentials(): LivenessCredentialsResponse
  fun getRegion(): String
}

class LivenessCredentialsService(
  private val stsClient: StsClient,
  private val roleArn: String,
  private val roleSessionName: String,
  private val region: String,
) : LivenessCredentialsProvider {

  override fun getCredentials(): LivenessCredentialsResponse {
    LOGGER.info("Assuming role for liveness browser credentials")

    val scopeDownPolicy = """
      {
        "Version": "2012-10-17",
        "Statement": [
          {
            "Effect": "Allow",
            "Action": [
              "rekognition:StartFaceLivenessSession"
            ],
            "Resource": "*"
          }
        ]
      }
    """.trimIndent()

    val assumeRoleRequest = AssumeRoleRequest.builder()
      .roleArn(roleArn)
      .roleSessionName("$roleSessionName-liveness-browser")
      .durationSeconds(900) // 15 minutes - minimum for STS
      .policy(scopeDownPolicy)
      .build()

    val response = stsClient.assumeRole(assumeRoleRequest)
    val credentials = response.credentials()

    LOGGER.info("Liveness browser credentials issued, expires at {}", credentials.expiration())

    return LivenessCredentialsResponse(
      accessKeyId = credentials.accessKeyId(),
      secretAccessKey = credentials.secretAccessKey(),
      sessionToken = credentials.sessionToken(),
      expiration = credentials.expiration().toString(),
    )
  }

  override fun getRegion(): String = region

  companion object {
    private val LOGGER = LoggerFactory.getLogger(LivenessCredentialsService::class.java)
  }
}

data class LivenessCredentialsResponse(
  val accessKeyId: String,
  val secretAccessKey: String,
  val sessionToken: String,
  val expiration: String,
)
