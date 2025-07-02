# Frontend/Backend Interactions

## Practitioner account setup

TODO

## Practitioner adds an Offender to the system

We assume the dev team manually (for now) created relevant records 
for practitioner.

```mermaid
sequenceDiagram
    participant Practitioner
    participant Frontend
    participant Backend
    participant S3
        
    Practitioner->>Frontend: Open Dashboard/Profile verification
    Frontend-->>Practitioner: Display profile verification page
    Practitioner->>Frontend: Add offender
    Frontend-->>Practitioner: Display form
    Practitioner->>Frontend: Fill invite form
    Frontend-->>Practitioner: Display photo page
    Practitioner->>Frontend: Submit Photo
    Frontend->>S3: Upload photo
    Frontend->>Backend: Create invite 
    Backend-->>Frontend: invite status
    Frontend-->>Practitioner: Display confirmation
```

## Offender does a remote checkin

At the start of this scenario, we assume the invite containing the unique checkin link 
was sent and received by the offender.

```mermaid
sequenceDiagram
    participant Offender
    participant Frontend
    participant Backend
    participant S3
    participant Rekognition
        
    Offender->>Frontend: Open invite URL
    Frontend->>Backend: Validate invite URL
    Backend-->>Frontend: Invite valid
    Frontend-->>Offender: Display questions
    Offender->>Frontend: Submit answer
    Note over Offender,Frontend: multiple question/answer interactions
    Frontend->>Backend: Get photo upload URLs
    Backend-->>Frontend: Return pre-signed photo URL
    Frontend->>Backend: Get Video upload URLs
    Backend-->>Frontend: Return pre-signed video URL
    Frontend->>S3: GET offender reference photo
    Frontend->>S3: POST offender reference photo for rekognition
    Frontend-->>Offender: Display /video/record page
    Offender->>S3: Upload video with presigned URL
    Offender->>S3: Upload snapshot photo(s) with presigned URL
    Offender->>Frontend: Signal data has been uploaded
    Note over Offender,Frontend: We can now call rekognition
    Frontend->>Rekognition: Compare faces
    Note over Frontend,Rekognition: Rekognition will use the reference and snapshot photo
    Offender->>Frontend: Submit
    Frontend->>Backend: POST /offender_checkins/:uuid/submit
    Backend-->>Frontend: Success
    Frontend-->>Offender: Display checking completion message
```