#!/bin/bash

# Run esupervision API in dev mode with all dependencies

echo "🚀 Starting hmpps-esupervision-api in dev mode..."
echo ""

# Export environment variables
export POSTGRES_USERNAME=postgres
export POSTGRES_PASSWORD=password
export POSTGRES_ENDPOINT=localhost:5432
export POSTGRES_DATABASE=esupervision
export NOTIFY_API_KEY=dev-key
export S3_DATA_BUCKET_NAME=local-bucket
export HOSTED_AT=http://localhost:8080
export API_CLIENT_ID=client
export API_CLIENT_SECRET=secret
export MANAGE_USERS_API=http://localhost:9090
export REKOG_AWS_REGION=eu-west-2
export REKOG_ROLE_ARN=role
export REKOG_ROLE_SESSION_NAME=session
export SPRING_PROFILES_ACTIVE=dev,local,stubndilius,stubtier,stubarns,stubsupervisionpackages
# Validate tokens against dev HMPPS Auth, the same issuer the manage-online-check-ins UI uses locally
export HMPPS_AUTH_URL=https://sign-in-dev.hmpps.service.justice.gov.uk/auth
export NDILIUS_API_URL=http://localhost:4010
# Tier API v3 from the same date as dev; the stub's v2 score is not a letter the UI can band
export APP_FEATURES_ESUP_2186_TIER_V3_FROM="2026-09-18T00:00:00+01:00"

export MPOP_URL=https://manage-people-on-probation-dev.hmpps.service.justice.gov.uk
export POP_UI_URL=https://probation-check-in-dev.hmpps.service.justice.gov.uk
export API_BASE_URL=http://localhost:8080
export APP_ENV=local

echo "✅ Environment variables set"
echo "📦 Starting API with Gradle..."
echo ""

./gradlew bootRun
