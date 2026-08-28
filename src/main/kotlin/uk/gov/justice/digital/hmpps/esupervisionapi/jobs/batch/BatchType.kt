package uk.gov.justice.digital.hmpps.esupervisionapi.jobs.batch

/**
 * One entry per job that runs as a one-shot K8s CronJob pod instead of @Scheduled.
 * Value must match the `type` set for the job in helm_deploy/hmpps-esupervision-api/values.yaml
 * (batchjobs[].type), which becomes the BATCH_TYPE env var.
 */
enum class BatchType {
  OFFENDER_ELIGIBILITY_SYNC,
}
