# Frontend/Backend Interactions

## Practitioner account setup


## Practitioner adds an Offender to the system

We assume the dev team manually (for now) created relevant records 
for practitioner.

```mermaid
sequenceDiagram
    participant Practitioner
    participant Frontend
    participant Backend
        
    Practitioner->>Frontend: Open Dashboard/Profile verification
    Frontend-->>Practitioner: Display profile verification page
    Practitioner->>Frontend: Create invite
    Frontend-->>Practitioner: Display invite form
    Practitioner->>Frontend: Fill invite form
    Frontend->>Backend: Create invite 
    Backend-->>Frontend: invite status
    Frontend-->>Practitioner: Display confirmation
```

## Offender responds to an invite

```mermaid
sequenceDiagram
    participant Offender
    participant Frontend
    participant Backend
        
    Offender->>Frontend: Open invite URL
    Frontend->>Backend: Validate invite URL
    Backend-->>Frontend: Invite valid
    Frontend-->>Offender: Display registration form
    Offender->>Frontend: Fill registration form
    Frontend->>Backend: Submit registration data
    Backend-->>Frontend: Registration success
    Frontend-->>Offender: Display success message
```