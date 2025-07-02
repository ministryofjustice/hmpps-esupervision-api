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
        
    Offender->>Frontend: Open invite URL
    Frontend->>Backend: Validate invite URL
    Backend-->>Frontend: Invite valid
    Frontend->>Backend: Get Photo Upload URL
    Backend-->>Frontend: Return pre-signed URL
    Frontend-->>Offender: Display photo upload UI
    Offender->>Frontend: Upload photo
    Frontend->>S3: Upload photo to pre-signed URL
    Frontend-->>Offender: Display registration form
    Offender->>Frontend: Fill registration form
    Frontend->>Backend: Submit registration data
    Backend-->>Frontend: Registration success
    Frontend-->>Offender: Display success message
```