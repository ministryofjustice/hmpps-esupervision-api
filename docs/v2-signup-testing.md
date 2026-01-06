# V2 Signup Journey - Testing Guide

## Overview

The V2 signup (registration) flow has 3 steps:
1. Start setup - create offender record with CRN
2. Upload photo - get presigned URL and upload image
3. Complete setup - verify photo uploaded and activate offender

## Step 1: Start Setup

**POST** `/v2/offender_setup`

```json
{
  "setupUuid": "550e8400-e29b-41d4-a716-446655440000",
  "practitionerId": "TEST_PRACTITIONER_001",
  "crn": "X123456",
  "firstCheckin": "2025-12-06",
  "checkinInterval": "WEEKLY"
}
```

**Fields:**
- `setupUuid` - Generate a new UUID for each setup (use any UUID generator)
- `practitionerId` - Any string identifier for the practitioner
- `crn` - Must match pattern `X123456` (letter + 6 digits)
- `firstCheckin` - Date in `YYYY-MM-DD` format (today or future)
- `checkinInterval` - One of: `WEEKLY`, `TWO_WEEKS`, `FOUR_WEEKS`, `EIGHT_WEEKS`

**Response:**
```json
{
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "practitionerId": "TEST_PRACTITIONER_001",
  "offenderUuid": "generated-offender-uuid",
  "createdAt": "2025-12-05T15:30:00Z",
  "startedAt": null
}
```

Save the `uuid` from the response for the next steps.

---

## Step 2: Get Photo Upload URL

**POST** `/v2/offender_setup/{uuid}/upload_location?content-type=image/jpeg`

Replace `{uuid}` with the setup UUID from Step 1.

**Response:**
```json
{
  "locationInfo": {
    "url": "https://s3.eu-west-2.amazonaws.com/bucket/path?presigned-params...",
    "contentType": "image/jpeg",
    "ttl": "PT5M"
  },
  "errorMessage": null
}
```

---

## Step 3: Upload Photo to S3

Use the presigned URL from Step 2 to upload the image.

**Using curl:**
```bash
curl -X PUT \
  -H "Content-Type: image/jpeg" \
  --data-binary @/path/to/photo.jpg \
  "PRESIGNED_URL_FROM_STEP_2"
```

**Creating a test image (base64):**

If you need a simple test image, create a 1x1 pixel JPEG:

```bash
# Create a minimal valid JPEG file
echo -n '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBEQCEAAwA/wBBgA//' | base64 -d > test.jpg
```

Then upload:
```bash
curl -X PUT \
  -H "Content-Type: image/jpeg" \
  --data-binary @test.jpg \
  "PRESIGNED_URL_FROM_STEP_2"
```

---

## Step 4: Complete Setup

**POST** `/v2/offender_setup/{uuid}/complete`

Replace `{uuid}` with the setup UUID.

No request body needed.

**Response:**
```json
{
  "uuid": "offender-uuid",
  "crn": "X123456",
  "practitionerId": "TEST_PRACTITIONER_001",
  "status": "VERIFIED",
  "firstCheckin": "2025-12-06",
  "checkinInterval": "WEEKLY",
  "createdAt": "2025-12-05T15:30:00Z",
  "createdBy": "TEST_PRACTITIONER_001",
  "updatedAt": "2025-12-05T15:35:00Z",
  "personalDetails": null
}
```

---

## Complete curl Example

```bash
# Variables
BASE_URL="http://localhost:8080"
SETUP_UUID=$(uuidgen | tr '[:upper:]' '[:lower:]')
CRN="X$(printf '%06d' $RANDOM)"

# Step 1: Start setup
echo "Starting setup with UUID: $SETUP_UUID and CRN: $CRN"
SETUP_RESPONSE=$(curl -s -X POST "$BASE_URL/v2/offender_setup" \
  -H "Content-Type: application/json" \
  -d "{
    \"setupUuid\": \"$SETUP_UUID\",
    \"practitionerId\": \"TEST_PRAC_001\",
    \"crn\": \"$CRN\",
    \"firstCheckin\": \"$(date -v+1d +%Y-%m-%d)\",
    \"checkinInterval\": \"WEEKLY\"
  }")
echo "Setup response: $SETUP_RESPONSE"

# Step 2: Get upload URL
UPLOAD_RESPONSE=$(curl -s -X POST "$BASE_URL/v2/offender_setup/$SETUP_UUID/upload_location?content-type=image/jpeg")
UPLOAD_URL=$(echo $UPLOAD_RESPONSE | jq -r '.locationInfo.url')
echo "Upload URL: $UPLOAD_URL"

# Step 3: Create and upload test image
echo -n '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBEQCEAAwA/wBBgA//' | base64 -d > /tmp/test.jpg
curl -X PUT -H "Content-Type: image/jpeg" --data-binary @/tmp/test.jpg "$UPLOAD_URL"
echo "Photo uploaded"

# Step 4: Complete setup
COMPLETE_RESPONSE=$(curl -s -X POST "$BASE_URL/v2/offender_setup/$SETUP_UUID/complete")
echo "Complete response: $COMPLETE_RESPONSE"
```

---

## Testing via Swagger UI

1. Navigate to `/swagger-ui.html`
2. Find "V2 Offender Setup" section
3. For image upload, you cannot upload directly via Swagger - use curl with the presigned URL

---

## Notes

- Each CRN can only be registered once
- Setup UUID must be unique per setup attempt
- Photo must be uploaded before calling `/complete`
- Presigned URLs expire after 5 minutes
