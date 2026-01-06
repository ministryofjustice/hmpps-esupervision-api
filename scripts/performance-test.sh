#!/bin/bash
set -e

# Performance Testing Script for E-Supervision API
# Simulates multiple practitioners and offenders working concurrently
#
# Requirements:
#   - jq (for JSON parsing)
#   - GNU parallel (optional, for better parallelization) or bash background jobs
#   - curl

# ============================================================
# CONFIGURATION - Modify these values
# ============================================================

API_CLIENT_ID="${API_CLIENT_ID:-your-client-id}"
API_CLIENT_SECRET="${API_CLIENT_SECRET:-your-client-secret}"

AUTH_URL="${AUTH_URL:-https://sign-in-dev.hmpps.service.justice.gov.uk}"
API_URL="${API_URL:-http://localhost:8080}"

# Performance test parameters
NUM_PRACTITIONERS="${NUM_PRACTITIONERS:-3}"
NUM_OFFENDERS_PER_PRACTITIONER="${NUM_OFFENDERS_PER_PRACTITIONER:-5}"
NUM_CHECKINS_PER_OFFENDER="${NUM_CHECKINS_PER_OFFENDER:-2}"

# Parallelization settings
MAX_PARALLEL_JOBS="${MAX_PARALLEL_JOBS:-10}"

# Test data directory
TEST_DATA_DIR="${TEST_DATA_DIR:-./perf-test-data}"

# ============================================================
# Colors for output
# ============================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ============================================================
# Global variables for metrics
# ============================================================
START_TIME=$(date +%s)
RESULTS_FILE="$TEST_DATA_DIR/results-$(date +%Y%m%d-%H%M%S).log"
METRICS_FILE="$TEST_DATA_DIR/metrics-$(date +%Y%m%d-%H%M%S).csv"

# ============================================================
# Helper functions
# ============================================================

log_info() {
  echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
  echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
  echo -e "\n${BLUE}========================================${NC}"
  echo -e "${BLUE}$1${NC}"
  echo -e "${BLUE}========================================${NC}"
}

log_metric() {
  local operation=$1
  local duration=$2
  local status=$3
  local details=$4
  echo "$(date +%s),$operation,$duration,$status,$details" >> "$METRICS_FILE"
}

usage() {
  cat << EOF
Usage: $0 [OPTIONS]

Performance testing script for E-Supervision API.
Simulates multiple practitioners and offenders working concurrently.

OPTIONS:
  -p, --practitioners NUM       Number of practitioners to simulate (default: 3)
  -o, --offenders NUM           Number of offenders per practitioner (default: 5)
  -c, --checkins NUM            Number of checkins per offender (default: 2)
  -j, --jobs NUM                Max parallel jobs (default: 10)
  --api-url URL                 API base URL (default: http://localhost:8080)
  --auth-url URL                Auth URL (default: https://sign-in-dev.hmpps.service.justice.gov.uk)
  --client-id ID                OAuth client ID
  --client-secret SECRET        OAuth client secret
  --data-dir DIR                Test data directory (default: ./perf-test-data)
  --cleanup                     Cleanup test data after completion
  -h, --help                    Show this help message

ENVIRONMENT VARIABLES:
  API_CLIENT_ID                 OAuth client ID
  API_CLIENT_SECRET             OAuth client secret
  API_URL                       API base URL
  AUTH_URL                      Auth URL
  NUM_PRACTITIONERS             Number of practitioners
  NUM_OFFENDERS_PER_PRACTITIONER Number of offenders per practitioner
  NUM_CHECKINS_PER_OFFENDER     Number of checkins per offender
  MAX_PARALLEL_JOBS             Max parallel jobs

EXAMPLE:
  # Run with 5 practitioners, 10 offenders each, 3 checkins each
  $0 -p 5 -o 10 -c 3 -j 20

  # Run with custom API URL
  $0 --api-url http://localhost:8080 --client-id myid --client-secret mysecret

EOF
  exit 1
}

# ============================================================
# Parse command line arguments
# ============================================================

CLEANUP=false

while [[ $# -gt 0 ]]; do
  case $1 in
    -p|--practitioners)
      NUM_PRACTITIONERS="$2"
      shift 2
      ;;
    -o|--offenders)
      NUM_OFFENDERS_PER_PRACTITIONER="$2"
      shift 2
      ;;
    -c|--checkins)
      NUM_CHECKINS_PER_OFFENDER="$2"
      shift 2
      ;;
    -j|--jobs)
      MAX_PARALLEL_JOBS="$2"
      shift 2
      ;;
    --api-url)
      API_URL="$2"
      shift 2
      ;;
    --auth-url)
      AUTH_URL="$2"
      shift 2
      ;;
    --client-id)
      API_CLIENT_ID="$2"
      shift 2
      ;;
    --client-secret)
      API_CLIENT_SECRET="$2"
      shift 2
      ;;
    --data-dir)
      TEST_DATA_DIR="$2"
      shift 2
      ;;
    --cleanup)
      CLEANUP=true
      shift
      ;;
    -h|--help)
      usage
      ;;
    *)
      log_error "Unknown option: $1"
      usage
      ;;
  esac
done

# ============================================================
# Validation
# ============================================================

if [[ "$API_CLIENT_ID" == "your-client-id" ]] || [[ -z "$API_CLIENT_ID" ]]; then
  log_error "API_CLIENT_ID not set. Use --client-id or set API_CLIENT_ID environment variable"
  exit 1
fi

if [[ "$API_CLIENT_SECRET" == "your-client-secret" ]] || [[ -z "$API_CLIENT_SECRET" ]]; then
  log_error "API_CLIENT_SECRET not set. Use --client-secret or set API_CLIENT_SECRET environment variable"
  exit 1
fi

# Check for required tools
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed. Install with: brew install jq"; exit 1; }
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed"; exit 1; }

# Check for parallel (optional)
HAS_PARALLEL=false
if command -v parallel >/dev/null 2>&1; then
  HAS_PARALLEL=true
  log_info "GNU Parallel detected - will use for better performance"
else
  log_warn "GNU Parallel not found - using bash background jobs (install with: brew install parallel)"
fi

# ============================================================
# Setup test environment
# ============================================================

log_step "SETUP TEST ENVIRONMENT"

mkdir -p "$TEST_DATA_DIR"
log_info "Test data directory: $TEST_DATA_DIR"
log_info "Results file: $RESULTS_FILE"
log_info "Metrics file: $METRICS_FILE"

# Initialize metrics CSV
echo "timestamp,operation,duration_ms,status,details" > "$METRICS_FILE"

# ============================================================
# Create test media files
# ============================================================

create_test_media() {
  log_info "Creating test media files..."
  
  # Create a minimal valid JPEG (1x1 pixel)
  TEST_PHOTO="$TEST_DATA_DIR/test-photo.jpg"
  if [[ ! -f "$TEST_PHOTO" ]]; then
    echo -n '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBEQCEAAwA/wBBgA//' | base64 -d > "$TEST_PHOTO"
    log_info "Created test photo: $TEST_PHOTO"
  fi
  
  # Create a minimal valid MP4 video (empty 1 second video)
  TEST_VIDEO="$TEST_DATA_DIR/test-video.mp4"
  if [[ ! -f "$TEST_VIDEO" ]]; then
    # Use ffmpeg if available, otherwise create a minimal placeholder
    if command -v ffmpeg >/dev/null 2>&1; then
      ffmpeg -f lavfi -i color=black:s=320x240:d=1 -c:v libx264 -preset ultrafast -y "$TEST_VIDEO" 2>/dev/null
      log_info "Created test video with ffmpeg: $TEST_VIDEO"
    else
      # Create minimal MP4 header (not valid for playback but accepted by S3)
      echo -n 'AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAA' | base64 -d > "$TEST_VIDEO"
      log_warn "ffmpeg not found, created minimal MP4 placeholder (install ffmpeg for valid video: brew install ffmpeg)"
    fi
  fi
  
  TEST_SNAPSHOT="$TEST_DATA_DIR/test-snapshot.jpg"
  if [[ ! -f "$TEST_SNAPSHOT" ]]; then
    cp "$TEST_PHOTO" "$TEST_SNAPSHOT"
    log_info "Created test snapshot: $TEST_SNAPSHOT"
  fi
}

create_test_media

# ============================================================
# Get authentication token
# ============================================================

get_auth_token() {
  local token_response
  token_response=$(curl -s -X POST "${AUTH_URL}/auth/oauth/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -u "${API_CLIENT_ID}:${API_CLIENT_SECRET}" \
    -d "grant_type=client_credentials")
  
  if ! echo "$token_response" | jq empty 2>/dev/null; then
    log_error "Auth response is not valid JSON: $token_response"
    return 1
  fi
  
  local token
  token=$(echo "$token_response" | jq -r '.access_token')
  
  if [[ -z "$token" || "$token" == "null" ]]; then
    log_error "Failed to get access token: $token_response"
    return 1
  fi
  
  echo "$token"
}

log_step "AUTHENTICATION"
log_info "Getting OAuth token..."
ACCESS_TOKEN=$(get_auth_token)
if [[ -z "$ACCESS_TOKEN" ]]; then
  log_error "Failed to authenticate"
  exit 1
fi
log_info "Got access token (${#ACCESS_TOKEN} chars)"

# ============================================================
# Content type detection
# ============================================================

get_content_type() {
  local file="$1"
  local ext="${file##*.}"
  case "$ext" in
    mp4) echo "video/mp4" ;;
    webm) echo "video/webm" ;;
    mov) echo "video/quicktime" ;;
    jpg|jpeg) echo "image/jpeg" ;;
    png) echo "image/png" ;;
    *) echo "application/octet-stream" ;;
  esac
}

PHOTO_CONTENT_TYPE=$(get_content_type "$TEST_PHOTO")
VIDEO_CONTENT_TYPE=$(get_content_type "$TEST_VIDEO")
SNAPSHOT_CONTENT_TYPE=$(get_content_type "$TEST_SNAPSHOT")

# ============================================================
# Core API operations
# ============================================================

# Setup offender (full flow)
setup_offender() {
  local practitioner_id=$1
  local offender_num=$2
  
  local start=$(date +%s%3N)
  local crn="X$(printf '%06d' $((practitioner_id * 1000000 + offender_num)))"
  local setup_uuid=$(uuidgen | tr '[:upper:]' '[:lower:]')
  local today=$(date +%Y-%m-%d)
  
  # Step 1: Start setup
  local setup_response
  setup_response=$(curl -s -X POST "${API_URL}/v2/offender_setup" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"setupUuid\": \"$setup_uuid\",
      \"practitionerId\": \"PRACTITIONER_${practitioner_id}\",
      \"crn\": \"$crn\",
      \"firstCheckin\": \"$today\",
      \"checkinInterval\": \"WEEKLY\"
    }")
  
  local offender_uuid
  offender_uuid=$(echo "$setup_response" | jq -r '.offenderUuid // .offender')
  
  if [[ -z "$offender_uuid" || "$offender_uuid" == "null" ]]; then
    local end=$(date +%s%3N)
    log_metric "setup_offender" "$((end - start))" "FAILED" "practitioner=$practitioner_id,offender=$offender_num,step=start_setup"
    echo "FAILED|||$crn|||$practitioner_id"
    return 1
  fi
  
  # Step 2: Get photo upload URL
  local photo_url_response
  photo_url_response=$(curl -s -X POST "${API_URL}/v2/offender_setup/${setup_uuid}/upload_location?content-type=${PHOTO_CONTENT_TYPE}" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
  
  local photo_upload_url
  photo_upload_url=$(echo "$photo_url_response" | jq -r '.locationInfo.url // .url')
  
  if [[ -z "$photo_upload_url" || "$photo_upload_url" == "null" ]]; then
    local end=$(date +%s%3N)
    log_metric "setup_offender" "$((end - start))" "FAILED" "practitioner=$practitioner_id,offender=$offender_num,step=get_photo_url"
    echo "FAILED|||$crn|||$practitioner_id"
    return 1
  fi
  
  # Step 3: Upload photo
  local upload_status
  upload_status=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$photo_upload_url" \
    -H "Content-Type: $PHOTO_CONTENT_TYPE" \
    --data-binary "@$TEST_PHOTO")
  
  if [[ "$upload_status" != "200" && "$upload_status" != "201" ]]; then
    local end=$(date +%s%3N)
    log_metric "setup_offender" "$((end - start))" "FAILED" "practitioner=$practitioner_id,offender=$offender_num,step=upload_photo"
    echo "FAILED|||$crn|||$practitioner_id"
    return 1
  fi
  
  # Step 4: Complete setup
  local complete_response
  complete_response=$(curl -s -X POST "${API_URL}/v2/offender_setup/${setup_uuid}/complete" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json")
  
  local status
  status=$(echo "$complete_response" | jq -r '.status')
  offender_uuid=$(echo "$complete_response" | jq -r '.uuid')
  
  local end=$(date +%s%3N)
  
  if [[ "$status" != "VERIFIED" ]]; then
    log_metric "setup_offender" "$((end - start))" "FAILED" "practitioner=$practitioner_id,offender=$offender_num,step=complete"
    echo "FAILED|||$crn|||$practitioner_id"
    return 1
  fi
  
  log_metric "setup_offender" "$((end - start))" "SUCCESS" "practitioner=$practitioner_id,offender=$offender_num"
  # Output format: offender_uuid|||crn|||practitioner_id|||forename|||surname|||dob
  echo "$offender_uuid|||$crn|||PRACTITIONER_${practitioner_id}|||John|||Doe|||1990-01-01"
}

# Create and submit a checkin
do_checkin() {
  local offender_uuid=$1
  local crn=$2
  local practitioner_id=$3
  local forename=${4:-"John"}
  local surname=${5:-"Doe"}
  local dob=${6:-"1990-01-01"}
  
  local start=$(date +%s%3N)
  local today=$(date +%Y-%m-%d)
  
  # Step 1: Create checkin
  local create_response
  create_response=$(curl -s -X POST "${API_URL}/v2/offender_checkins" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"offender\": \"$offender_uuid\",
      \"practitioner\": \"$practitioner_id\",
      \"dueDate\": \"$today\"
    }")
  
  local checkin_uuid
  checkin_uuid=$(echo "$create_response" | jq -r '.uuid')
  
  if [[ -z "$checkin_uuid" || "$checkin_uuid" == "null" ]]; then
    local end=$(date +%s%3N)
    log_metric "do_checkin" "$((end - start))" "FAILED" "offender=$offender_uuid,step=create"
    return 1
  fi
  
  # Step 2: Get upload URLs
  local upload_response
  upload_response=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${checkin_uuid}/upload_location?video=${VIDEO_CONTENT_TYPE}&snapshots=${SNAPSHOT_CONTENT_TYPE}" \
    -H "Authorization: Bearer $ACCESS_TOKEN")
  
  local video_url snapshot_url
  video_url=$(echo "$upload_response" | jq -r '.video.url')
  snapshot_url=$(echo "$upload_response" | jq -r '.snapshots[0].url')
  
  if [[ -z "$video_url" || "$video_url" == "null" ]] || [[ -z "$snapshot_url" || "$snapshot_url" == "null" ]]; then
    local end=$(date +%s%3N)
    log_metric "do_checkin" "$((end - start))" "FAILED" "offender=$offender_uuid,step=get_urls"
    return 1
  fi
  
  # Step 3: Upload video
  curl -s -o /dev/null -X PUT "$video_url" \
    -H "Content-Type: $VIDEO_CONTENT_TYPE" \
    --data-binary "@$TEST_VIDEO"
  
  # Step 4: Upload snapshot
  curl -s -o /dev/null -X PUT "$snapshot_url" \
    -H "Content-Type: $SNAPSHOT_CONTENT_TYPE" \
    --data-binary "@$TEST_SNAPSHOT"
  
  # Step 5: Verify identity
  local identity_response
  identity_response=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${checkin_uuid}/identity-verify" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"crn\": \"$crn\",
      \"name\": {
        \"forename\": \"$forename\",
        \"surname\": \"$surname\"
      },
      \"dateOfBirth\": \"$dob\"
    }")
  
  # Step 6: Run facial verification
  curl -s -X POST "${API_URL}/v2/offender_checkins/${checkin_uuid}/video-verify?numSnapshots=1" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" > /dev/null
  
  # Step 7: Submit checkin
  local submit_response
  submit_response=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${checkin_uuid}/submit" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "survey": {
        "mentalHealth": "GOOD",
        "assistance": ["NO_HELP"],
        "callback": "NO"
      }
    }')
  
  local submit_status
  submit_status=$(echo "$submit_response" | jq -r '.status')
  
  if [[ "$submit_status" != "SUBMITTED" ]]; then
    local end=$(date +%s%3N)
    log_metric "do_checkin" "$((end - start))" "FAILED" "offender=$offender_uuid,step=submit"
    return 1
  fi
  
  # Step 8: Start review
  curl -s -X POST "${API_URL}/v2/offender_checkins/${checkin_uuid}/review-started" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"practitionerId\": \"$practitioner_id\"}" > /dev/null
  
  # Step 9: Complete review
  local review_response
  review_response=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${checkin_uuid}/review" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"reviewedBy\": \"$practitioner_id\",
      \"manualIdCheck\": \"MATCH\",
      \"notes\": \"Performance test - auto-reviewed\"
    }")
  
  local review_status
  review_status=$(echo "$review_response" | jq -r '.status')
  
  local end=$(date +%s%3N)
  
  if [[ "$review_status" != "REVIEWED" ]]; then
    log_metric "do_checkin" "$((end - start))" "FAILED" "offender=$offender_uuid,step=review"
    return 1
  fi
  
  log_metric "do_checkin" "$((end - start))" "SUCCESS" "offender=$offender_uuid,checkin=$checkin_uuid"
}

# ============================================================
# Main performance test execution
# ============================================================

log_step "PERFORMANCE TEST CONFIGURATION"
log_info "Number of practitioners: $NUM_PRACTITIONERS"
log_info "Offenders per practitioner: $NUM_OFFENDERS_PER_PRACTITIONER"
log_info "Checkins per offender: $NUM_CHECKINS_PER_OFFENDER"
log_info "Max parallel jobs: $MAX_PARALLEL_JOBS"
log_info "Total offenders: $((NUM_PRACTITIONERS * NUM_OFFENDERS_PER_PRACTITIONER))"
log_info "Total checkins: $((NUM_PRACTITIONERS * NUM_OFFENDERS_PER_PRACTITIONER * NUM_CHECKINS_PER_OFFENDER))"

# Create offenders list file
OFFENDERS_FILE="$TEST_DATA_DIR/offenders.txt"
> "$OFFENDERS_FILE"

# ============================================================
# Phase 1: Setup offenders
# ============================================================

log_step "PHASE 1: SETTING UP OFFENDERS"

setup_count=0
setup_failed=0

for practitioner in $(seq 1 "$NUM_PRACTITIONERS"); do
  log_info "Setting up offenders for PRACTITIONER_${practitioner}..."
  
  for offender in $(seq 1 "$NUM_OFFENDERS_PER_PRACTITIONER"); do
    (
      result=$(setup_offender "$practitioner" "$offender")
      if [[ $? -eq 0 && "$result" != FAILED* ]]; then
        echo "$result" >> "$OFFENDERS_FILE"
        echo -e "${GREEN}✓${NC} Setup offender $offender for practitioner $practitioner"
      else
        echo -e "${RED}✗${NC} Failed to setup offender $offender for practitioner $practitioner"
      fi
    ) &
    
    # Limit parallel jobs
    while [[ $(jobs -r | wc -l) -ge $MAX_PARALLEL_JOBS ]]; do
      sleep 0.1
    done
  done
done

# Wait for all setup jobs to complete
wait

setup_count=$(wc -l < "$OFFENDERS_FILE" | tr -d ' ')
setup_failed=$((NUM_PRACTITIONERS * NUM_OFFENDERS_PER_PRACTITIONER - setup_count))

log_info "Setup complete: $setup_count succeeded, $setup_failed failed"

if [[ $setup_count -eq 0 ]]; then
  log_error "No offenders were set up successfully. Exiting."
  exit 1
fi

# ============================================================
# Phase 2: Perform checkins
# ============================================================

log_step "PHASE 2: PERFORMING CHECKINS"

checkin_count=0
checkin_failed=0

for checkin_num in $(seq 1 "$NUM_CHECKINS_PER_OFFENDER"); do
  log_info "Checkin round $checkin_num of $NUM_CHECKINS_PER_OFFENDER..."
  
  while IFS='|||' read -r offender_uuid crn practitioner_id forename surname dob; do
    (
      if do_checkin "$offender_uuid" "$crn" "$practitioner_id" "$forename" "$surname" "$dob"; then
        echo -e "${GREEN}✓${NC} Checkin $checkin_num for offender $offender_uuid"
      else
        echo -e "${RED}✗${NC} Failed checkin $checkin_num for offender $offender_uuid"
      fi
    ) &
    
    # Limit parallel jobs
    while [[ $(jobs -r | wc -l) -ge $MAX_PARALLEL_JOBS ]]; do
      sleep 0.1
    done
  done < "$OFFENDERS_FILE"
  
  # Wait for this round to complete before starting next
  wait
  
  # Add a small delay between rounds to simulate realistic usage
  if [[ $checkin_num -lt $NUM_CHECKINS_PER_OFFENDER ]]; then
    log_info "Pausing 2 seconds before next checkin round..."
    sleep 2
  fi
done

# ============================================================
# Calculate metrics and display results
# ============================================================

log_step "PERFORMANCE TEST RESULTS"

END_TIME=$(date +%s)
TOTAL_DURATION=$((END_TIME - START_TIME))

# Count successes and failures from metrics file
setup_success=$(grep "setup_offender.*SUCCESS" "$METRICS_FILE" | wc -l | tr -d ' ')
setup_fail=$(grep "setup_offender.*FAILED" "$METRICS_FILE" | wc -l | tr -d ' ')
checkin_success=$(grep "do_checkin.*SUCCESS" "$METRICS_FILE" | wc -l | tr -d ' ')
checkin_fail=$(grep "do_checkin.*FAILED" "$METRICS_FILE" | wc -l | tr -d ' ')

# Calculate average durations
avg_setup_duration=$(grep "setup_offender.*SUCCESS" "$METRICS_FILE" | awk -F',' '{sum+=$3; count++} END {if(count>0) print sum/count; else print 0}')
avg_checkin_duration=$(grep "do_checkin.*SUCCESS" "$METRICS_FILE" | awk -F',' '{sum+=$3; count++} END {if(count>0) print sum/count; else print 0}')

echo ""
log_info "Total Duration: ${TOTAL_DURATION}s"
echo ""
log_info "Offender Setup:"
log_info "  ✓ Success: $setup_success"
log_info "  ✗ Failed:  $setup_fail"
log_info "  ⏱ Avg Duration: ${avg_setup_duration}ms"
echo ""
log_info "Checkin Operations:"
log_info "  ✓ Success: $checkin_success"
log_info "  ✗ Failed:  $checkin_fail"
log_info "  ⏱ Avg Duration: ${avg_checkin_duration}ms"
echo ""

total_operations=$((setup_success + checkin_success))
if [[ $TOTAL_DURATION -gt 0 ]]; then
  throughput=$(echo "scale=2; $total_operations / $TOTAL_DURATION" | bc)
  log_info "Overall Throughput: ${throughput} operations/second"
fi

echo ""
log_info "Detailed metrics saved to: $METRICS_FILE"
log_info "Offenders list saved to: $OFFENDERS_FILE"

# ============================================================
# Cleanup (optional)
# ============================================================

if [[ "$CLEANUP" == "true" ]]; then
  log_step "CLEANUP"
  log_info "Cleaning up test data..."
  rm -rf "$TEST_DATA_DIR"
  log_info "Cleanup complete"
fi

echo ""
log_info "Performance test completed!"
