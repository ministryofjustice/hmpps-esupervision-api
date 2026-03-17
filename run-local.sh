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
#export S3_DATA_BUCKET_NAME=cloud-platform-d45760bf4de1d1fa391e43417b29b7e1
export S3_DATA_BUCKET_NAME=fcl-rekognition-test
export HOSTED_AT=http://localhost:8080
export API_CLIENT_ID=client
export API_CLIENT_SECRET=secret
export MANAGE_USERS_API=http://localhost:9090
export REKOG_AWS_REGION=eu-west-2
#export REKOG_ROLE_ARN=arn:aws:iam::514115671816:role/rekognition-role
export REKOG_ROLE_ARN=arn:aws:iam::063407603944:role/rekognition-test-role
export REKOG_ROLE_SESSION_NAME=cloud-platform-dev
#export REKOG_S3_DATA_BUCKET=hmpps-esupervision-development-rekognition-uploads
export REKOG_S3_DATA_BUCKET=fcl-rekognition-test
export NDILIUS_API_URL=http://localhost:4010
export POP_UI_URL=http://127.0.0.1000
export MPOP_URL=http://127.0.0.1:1111
export API_BASE_URL=http://127.0.0.1:8080

#export AWS_S3_PROFILE=default
export AWS_S3_PROFILE=fcl-rekognition-test
#export AWS_REKOGNITION_PROFILE=514115671816_modernisation-platform-developer
export AWS_REKOGNITION_PROFILE=fcl-rekognition-test

export SPRING_PROFILES_ACTIVE=dev,local,stubndilius

echo "✅ Environment variables set"
echo "📦 Starting API with Gradle..."
echo ""

./gradlew bootRun
