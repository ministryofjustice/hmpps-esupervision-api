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
export REKOG_S3_DATA_BUCKET=bucket
export SPRING_PROFILES_ACTIVE=dev
export NDILIUS_API_URL=http://localhost:4010

echo "✅ Environment variables set"
echo "📦 Starting API with Gradle..."
echo ""

./gradlew bootRun
