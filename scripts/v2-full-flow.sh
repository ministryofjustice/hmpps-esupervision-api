#!/bin/bash
set -e

# V2 Full Flow Script
# Complete offender lifecycle: Setup -> Checkin -> Review -> Terminate
#
# Flow:
# 1. Start offender setup (create offender record)
# 2. Upload setup photo
# 3. Complete setup (verify offender)
# 4. Create checkin
# 5. Upload video and snapshot
# 6. Verify identity
# 7. Run facial verification
# 8. Submit checkin
# 9. Practitioner reviews checkin
# 10. Terminate offender setup

# ============================================================
# CONFIGURATION - Modify these values directly
# ============================================================

API_CLIENT_ID="your-client-id"
API_CLIENT_SECRET="your-client-secret"

AUTH_URL="https://sign-in-dev.hmpps.service.justice.gov.uk"
API_URL="http://localhost:8080"

# ============================================================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

usage() {
  echo "Usage: $0 -c <crn> -f <forename> -l <surname> -d <dob> --photo <photo-path> -v <video-path> -s <snapshot-path> [-p <practitioner-id>] [--skip-terminate]"
  echo ""
  echo "Options:"
  echo "  -c, --crn            Case Reference Number e.g. X123456 (required)"
  echo "  -f, --forename       Offender's forename (required)"
  echo "  -l, --surname        Offender's surname (required)"
  echo "  -d, --dob            Date of birth YYYY-MM-DD (required)"
  echo "  -ph, --photo         Path to setup photo file (required)"
  echo "  -v, --video          Path to video file (required)"
  echo "  -s, --snapshot       Path to snapshot image file (required)"
  echo "  -p, --practitioner   Practitioner ID (default: TEST_PRACTITIONER)"
  echo "  --skip-terminate     Skip the terminate step at the end"
  echo "  -h, --help           Show this help message"
  echo ""
  echo "Note: Edit the CONFIGURATION section at the top of this script"
  echo "      to set API_CLIENT_ID, API_CLIENT_SECRET, AUTH_URL, and API_URL"
  exit 1
}

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

# Parse arguments
CRN=""
FORENAME=""
SURNAME=""
DOB=""
PHOTO_PATH=""
VIDEO_PATH=""
SNAPSHOT_PATH=""
PRACTITIONER_ID="TEST_PRACTITIONER"
SKIP_TERMINATE=false

while [[ $# -gt 0 ]]; do
  case $1 in
    -c|--crn)
      CRN="$2"
      shift 2
      ;;
    -f|--forename)
      FORENAME="$2"
      shift 2
      ;;
    -l|--surname)
      SURNAME="$2"
      shift 2
      ;;
    -d|--dob)
      DOB="$2"
      shift 2
      ;;
    -ph|--photo)
      PHOTO_PATH="$2"
      shift 2
      ;;
    -v|--video)
      VIDEO_PATH="$2"
      shift 2
      ;;
    -s|--snapshot)
      SNAPSHOT_PATH="$2"
      shift 2
      ;;
    -p|--practitioner)
      PRACTITIONER_ID="$2"
      shift 2
      ;;
    --skip-terminate)
      SKIP_TERMINATE=true
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

# Validate required arguments
if [[ -z "$CRN" ]]; then
  log_error "CRN is required"
  usage
fi

if [[ -z "$FORENAME" ]]; then
  log_error "Forename is required"
  usage
fi

if [[ -z "$SURNAME" ]]; then
  log_error "Surname is required"
  usage
fi

if [[ -z "$DOB" ]]; then
  log_error "Date of birth is required"
  usage
fi

if [[ -z "$PHOTO_PATH" ]]; then
  log_error "Photo path is required"
  usage
fi

if [[ -z "$VIDEO_PATH" ]]; then
  log_error "Video path is required"
  usage
fi

if [[ -z "$SNAPSHOT_PATH" ]]; then
  log_error "Snapshot path is required"
  usage
fi

# Validate files exist
if [[ ! -f "$PHOTO_PATH" ]]; then
  log_error "Photo file not found: $PHOTO_PATH"
  exit 1
fi

if [[ ! -f "$VIDEO_PATH" ]]; then
  log_error "Video file not found: $VIDEO_PATH"
  exit 1
fi

if [[ ! -f "$SNAPSHOT_PATH" ]]; then
  log_error "Snapshot file not found: $SNAPSHOT_PATH"
  exit 1
fi

# Validate configuration
if [[ "$API_CLIENT_ID" == "your-client-id" ]]; then
  log_error "Please edit the script and set API_CLIENT_ID in the CONFIGURATION section"
  exit 1
fi

if [[ "$API_CLIENT_SECRET" == "your-client-secret" ]]; then
  log_error "Please edit the script and set API_CLIENT_SECRET in the CONFIGURATION section"
  exit 1
fi

# Detect content types
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

PHOTO_CONTENT_TYPE=$(get_content_type "$PHOTO_PATH")
VIDEO_CONTENT_TYPE=$(get_content_type "$VIDEO_PATH")
SNAPSHOT_CONTENT_TYPE=$(get_content_type "$SNAPSHOT_PATH")

log_step "CONFIGURATION"
log_info "Auth URL: $AUTH_URL"
log_info "API URL: $API_URL"
log_info "CRN: $CRN"
log_info "Name: $FORENAME $SURNAME"
log_info "DOB: $DOB"
log_info "Photo: $PHOTO_PATH ($PHOTO_CONTENT_TYPE)"
log_info "Video: $VIDEO_PATH ($VIDEO_CONTENT_TYPE)"
log_info "Snapshot: $SNAPSHOT_PATH ($SNAPSHOT_CONTENT_TYPE)"
log_info "Practitioner: $PRACTITIONER_ID"
log_info "Skip Terminate: $SKIP_TERMINATE"

# ============================================================
# PART 1: AUTHENTICATION
# ============================================================
log_step "PART 1: AUTHENTICATION"

log_info "Getting JWT token from HMPPS Auth..."

TOKEN_RESPONSE=$(curl -s -X POST "${AUTH_URL}/auth/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "${API_CLIENT_ID}:${API_CLIENT_SECRET}" \
  -d "grant_type=client_credentials")

# Check if response is valid JSON before parsing
if ! echo "$TOKEN_RESPONSE" | jq empty 2>/dev/null; then
  log_error "Auth response is not valid JSON"
  log_error "Response: $TOKEN_RESPONSE"
  log_error "Check your AUTH_URL, API_CLIENT_ID, and API_CLIENT_SECRET configuration"
  exit 1
fi

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token')

if [[ -z "$ACCESS_TOKEN" || "$ACCESS_TOKEN" == "null" ]]; then
  log_error "Failed to get access token"
  log_error "Response: $TOKEN_RESPONSE"
  exit 1
fi

log_info "Got access token (${#ACCESS_TOKEN} chars)"

# ============================================================
# PART 2: OFFENDER SETUP
# ============================================================
log_step "PART 2: OFFENDER SETUP"

# Generate a UUID for the setup
SETUP_UUID=$(uuidgen | tr '[:upper:]' '[:lower:]')
TODAY=$(date +%Y-%m-%d)

log_info "Step 2.1: Starting offender setup..."

SETUP_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_setup" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"setupUuid\": \"$SETUP_UUID\",
    \"practitionerId\": \"$PRACTITIONER_ID\",
    \"crn\": \"$CRN\",
    \"firstCheckin\": \"$TODAY\",
    \"checkinInterval\": \"WEEKLY\"
  }")

SETUP_UUID_RETURNED=$(echo "$SETUP_RESPONSE" | jq -r '.uuid // .setupUuid')
OFFENDER_UUID=$(echo "$SETUP_RESPONSE" | jq -r '.offenderUuid // .offender')

if [[ -z "$SETUP_UUID_RETURNED" || "$SETUP_UUID_RETURNED" == "null" ]]; then
  log_error "Failed to start offender setup"
  log_error "Response: $SETUP_RESPONSE"
  exit 1
fi

SETUP_UUID="$SETUP_UUID_RETURNED"
log_info "Setup started: setupUuid=$SETUP_UUID, offenderUuid=$OFFENDER_UUID"

# Step 2.2: Get photo upload location
log_info "Step 2.2: Getting photo upload location..."

PHOTO_UPLOAD_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_setup/${SETUP_UUID}/upload_location?content-type=${PHOTO_CONTENT_TYPE}" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json")

PHOTO_UPLOAD_URL=$(echo "$PHOTO_UPLOAD_RESPONSE" | jq -r '.locationInfo.url // .url')

if [[ -z "$PHOTO_UPLOAD_URL" || "$PHOTO_UPLOAD_URL" == "null" ]]; then
  log_error "Failed to get photo upload URL"
  log_error "Response: $PHOTO_UPLOAD_RESPONSE"
  exit 1
fi

log_info "Got photo upload URL"

# Step 2.3: Upload photo
log_info "Step 2.3: Uploading setup photo..."

PHOTO_UPLOAD_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$PHOTO_UPLOAD_URL" \
  -H "Content-Type: $PHOTO_CONTENT_TYPE" \
  --data-binary "@$PHOTO_PATH")

if [[ "$PHOTO_UPLOAD_STATUS" != "200" && "$PHOTO_UPLOAD_STATUS" != "201" ]]; then
  log_error "Failed to upload photo. HTTP status: $PHOTO_UPLOAD_STATUS"
  exit 1
fi

log_info "Photo uploaded successfully"

# Step 2.4: Complete setup
log_info "Step 2.4: Completing offender setup..."

COMPLETE_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_setup/${SETUP_UUID}/complete" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json")

OFFENDER_STATUS=$(echo "$COMPLETE_RESPONSE" | jq -r '.status')
OFFENDER_UUID=$(echo "$COMPLETE_RESPONSE" | jq -r '.uuid')

if [[ "$OFFENDER_STATUS" != "VERIFIED" ]]; then
  log_error "Failed to complete offender setup"
  log_error "Response: $COMPLETE_RESPONSE"
  exit 1
fi

log_info "Offender setup completed: offenderUuid=$OFFENDER_UUID, status=$OFFENDER_STATUS"

# ============================================================
# PART 3: CHECKIN FLOW
# ============================================================
log_step "PART 3: CHECKIN FLOW"

# Step 3.1: Create checkin
log_info "Step 3.1: Creating checkin for offender $OFFENDER_UUID..."

CREATE_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_checkins" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"offender\": \"$OFFENDER_UUID\",
    \"practitioner\": \"$PRACTITIONER_ID\",
    \"dueDate\": \"$TODAY\"
  }")

CHECKIN_UUID=$(echo "$CREATE_RESPONSE" | jq -r '.uuid')

if [[ -z "$CHECKIN_UUID" || "$CHECKIN_UUID" == "null" ]]; then
  log_error "Failed to create checkin"
  log_error "Response: $CREATE_RESPONSE"
  exit 1
fi

log_info "Created checkin: $CHECKIN_UUID"

# Step 3.2: Get upload locations
log_info "Step 3.2: Getting upload locations..."

UPLOAD_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${CHECKIN_UUID}/upload_location?video=${VIDEO_CONTENT_TYPE}&snapshots=${SNAPSHOT_CONTENT_TYPE}" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json")

VIDEO_UPLOAD_URL=$(echo "$UPLOAD_RESPONSE" | jq -r '.video.url')
SNAPSHOT_UPLOAD_URL=$(echo "$UPLOAD_RESPONSE" | jq -r '.snapshots[0].url')

if [[ -z "$VIDEO_UPLOAD_URL" || "$VIDEO_UPLOAD_URL" == "null" ]]; then
  log_error "Failed to get video upload URL"
  log_error "Response: $UPLOAD_RESPONSE"
  exit 1
fi

if [[ -z "$SNAPSHOT_UPLOAD_URL" || "$SNAPSHOT_UPLOAD_URL" == "null" ]]; then
  log_error "Failed to get snapshot upload URL"
  log_error "Response: $UPLOAD_RESPONSE"
  exit 1
fi

log_info "Got upload URLs"

# Step 3.3: Upload video
log_info "Step 3.3: Uploading video..."

VIDEO_UPLOAD_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$VIDEO_UPLOAD_URL" \
  -H "Content-Type: $VIDEO_CONTENT_TYPE" \
  --data-binary "@$VIDEO_PATH")

if [[ "$VIDEO_UPLOAD_STATUS" != "200" && "$VIDEO_UPLOAD_STATUS" != "201" ]]; then
  log_error "Failed to upload video. HTTP status: $VIDEO_UPLOAD_STATUS"
  exit 1
fi

log_info "Video uploaded successfully"

# Step 3.4: Upload snapshot
log_info "Step 3.4: Uploading snapshot..."

SNAPSHOT_UPLOAD_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$SNAPSHOT_UPLOAD_URL" \
  -H "Content-Type: $SNAPSHOT_CONTENT_TYPE" \
  --data-binary "@$SNAPSHOT_PATH")

if [[ "$SNAPSHOT_UPLOAD_STATUS" != "200" && "$SNAPSHOT_UPLOAD_STATUS" != "201" ]]; then
  log_error "Failed to upload snapshot. HTTP status: $SNAPSHOT_UPLOAD_STATUS"
  exit 1
fi

log_info "Snapshot uploaded successfully"

# Step 3.5: Verify identity
log_info "Step 3.5: Verifying identity..."

IDENTITY_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${CHECKIN_UUID}/identity-verify" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"crn\": \"$CRN\",
    \"name\": {
      \"forename\": \"$FORENAME\",
      \"surname\": \"$SURNAME\"
    },
    \"dateOfBirth\": \"$DOB\"
  }")

IDENTITY_VERIFIED=$(echo "$IDENTITY_RESPONSE" | jq -r '.verified // false')

if [[ "$IDENTITY_VERIFIED" != "true" ]]; then
  log_error "Identity verification failed"
  log_error "Response: $IDENTITY_RESPONSE"
  exit 1
fi

log_info "Identity verified successfully"

# Step 3.6: Run facial verification
log_info "Step 3.6: Running facial verification..."

VERIFY_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${CHECKIN_UUID}/video-verify?numSnapshots=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json")

VERIFY_RESULT=$(echo "$VERIFY_RESPONSE" | jq -r '.result // "UNKNOWN"')
log_info "Facial verification result: $VERIFY_RESULT"

# Step 3.7: Submit checkin
log_info "Step 3.7: Submitting checkin..."

SUBMIT_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${CHECKIN_UUID}/submit" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "survey": {
      "mentalHealth": "GOOD",
      "assistance": ["NO_HELP"],
      "callback": "NO"
    }
  }')

SUBMIT_STATUS=$(echo "$SUBMIT_RESPONSE" | jq -r '.status')

if [[ "$SUBMIT_STATUS" != "SUBMITTED" ]]; then
  log_error "Failed to submit checkin"
  log_error "Response: $SUBMIT_RESPONSE"
  exit 1
fi

log_info "Checkin submitted successfully"

# ============================================================
# PART 4: PRACTITIONER REVIEW
# ============================================================
log_step "PART 4: PRACTITIONER REVIEW"

# Step 4.1: Start review
log_info "Step 4.1: Starting review..."

REVIEW_START_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${CHECKIN_UUID}/review-started" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"practitionerId\": \"$PRACTITIONER_ID\"
  }")

REVIEW_START_STATUS=$(echo "$REVIEW_START_RESPONSE" | jq -r '.status')

if [[ -z "$REVIEW_START_STATUS" || "$REVIEW_START_STATUS" == "null" ]]; then
  log_error "Failed to start review"
  log_error "Response: $REVIEW_START_RESPONSE"
  exit 1
fi

log_info "Review started"

# Step 4.2: Complete review
log_info "Step 4.2: Completing review..."

REVIEW_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offender_checkins/${CHECKIN_UUID}/review" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"reviewedBy\": \"$PRACTITIONER_ID\",
    \"manualIdCheck\": \"MATCH\",
    \"notes\": \"Automated full flow test - verified by script\"
  }")

REVIEW_STATUS=$(echo "$REVIEW_RESPONSE" | jq -r '.status')

if [[ "$REVIEW_STATUS" != "REVIEWED" ]]; then
  log_error "Failed to complete review"
  log_error "Response: $REVIEW_RESPONSE"
  exit 1
fi

log_info "Checkin reviewed: status=$REVIEW_STATUS"

# ============================================================
# PART 5: DEACTIVATE OFFENDER (OPTIONAL)
# ============================================================
if [[ "$SKIP_TERMINATE" == "false" ]]; then
  log_step "PART 5: DEACTIVATE OFFENDER"

  log_info "Deactivating offender..."

  DEACTIVATE_RESPONSE=$(curl -s -X POST "${API_URL}/v2/offenders/${OFFENDER_UUID}/deactivate" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"requestedBy\": \"$PRACTITIONER_ID\",
      \"reason\": \"Automated test - deactivated by script\"
    }")

  DEACTIVATE_STATUS=$(echo "$DEACTIVATE_RESPONSE" | jq -r '.status')

  if [[ "$DEACTIVATE_STATUS" != "INACTIVE" ]]; then
    log_warn "Deactivate returned unexpected status: $DEACTIVATE_STATUS"
    log_warn "Response: $DEACTIVATE_RESPONSE"
  else
    log_info "Offender deactivated: status=$DEACTIVATE_STATUS"
  fi
else
  log_step "PART 5: DEACTIVATE OFFENDER (SKIPPED)"
  log_info "Skipping deactivate as requested"
fi

# ============================================================
# SUMMARY
# ============================================================
log_step "SUMMARY"

echo ""
log_info "Full flow completed successfully!"
echo ""
log_info "Setup UUID:           $SETUP_UUID"
log_info "Offender UUID:        $OFFENDER_UUID"
log_info "Offender CRN:         $CRN"
log_info "Checkin UUID:         $CHECKIN_UUID"
log_info "Checkin Status:       $REVIEW_STATUS"
log_info "Facial Verification:  $VERIFY_RESULT"
if [[ "$SKIP_TERMINATE" == "false" ]]; then
  log_info "Offender Status:      INACTIVE (deactivated)"
else
  log_info "Offender Status:      VERIFIED (not deactivated)"
fi
echo ""
