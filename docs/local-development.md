# Local Development

The application relies on a number of external services (e.g. NDelius, AWS Rekognition) to function. 
But it's possible to configure it to use stubbed or containerised versions of them.

Localstack is used for stubbing AWS services (execept AWS Rekognition). For other's please read on.

### HMPPS Auth (in a container)

Start the dependencies without the application container:

```sh
podman compose up --scale hmpps-esupervision-api=0
```

Once Auth is ready, run this separate command from the repository root (requires Python 3, no extra packages):

```sh
./scripts/auth-local-setup.py
```
The default local credentials are username `AUTH_ADM` and password `password123456`.
The command targets only `http://localhost:8090/auth`; it does not depend on Docker.

The command:

- adds `ESUPERVISION__ESUPERVISION_UI` to the Authorities of the `hmpps-typescript-template-system` client
- ensures that client allows both `read` and `write` scopes, preserving other scopes
- adds `ESUPERVISION__PRACTITIONER__RW` to the "Authorised roles" of the `hmpps-typescript-template`
  service details (equivalent to "Add service details" on the client page), creating the service if needed,
  and makes sure the service is enabled

It preserves existing roles and other settings, then verifies the saved configuration.
It is safe to rerun: existing roles are left unchanged. Rerun after recreating the Auth container.

If esupervision's shared outbound `API_CLIENT_ID` is a different existing local Auth client,
configure it as well:

```sh
./scripts/auth-local-setup.py --client-id "$API_CLIENT_ID"
```

The script also reads `API_CLIENT_ID` from its environment when `--client-id` is omitted.
It preserves the existing UI-client and service setup, and adds the authority and missing
`read`/`write` scopes to the selected outbound client. It does not create clients or change
their secrets; an unknown client fails explicitly.

For manual inspection, log in at http://localhost:8090/auth/ui, then open
http://localhost:8090/auth/new-ui/clients or http://localhost:8090/auth/ui/services.

### S3

Assuming localstack is running (via `docker compose`), run `scripts/s3-local-setup.sh` to create and configure the necessary buckets.

**Note**: The names should match the ones in your environment and/or `application*.yml` file in `aws.s3` section

```
image-uploads: ${S3_DATA_BUCKET_NAME}
video-uploads: ${S3_DATA_BUCKET_NAME}
```

### API

The following environment variables need to be set

```
API_CLIENT_ID=client
API_CLIENT_SECRET=secret
```

### Stub NDelius API

Add `stubNDelius` Spring profile - replaces NDelius client with one generating data based on the CRN in `src/test/resources/ndelius-responses/default.json`

The data generated for `stubNDelius` profile uses the CRN to generate offender and practitioner data (see `MultiSampleStubDataProvider` docs
for details). The data generator can reload the JSON file at runtime, so you can add/remove items without restarting the application.

**Note**: the date of birth is not required on our side, so if CRN is in the stub JSON file, any date of birth will be accepted on the
verification screen in the UI. 

### Offender setup

Before we can submit a check-in, we need to onboard an offender to the system. That requires 
1. Submitting a request to the API `POST /v2/offender_setup`
2. Uploading a reference photo to S3

See [Offender setup](http/v2-offender-setup.http) script which can be run via Intellij Idea HTTP client (there's also a CLI version
which you can install without the IDE: [HTTP Client CLI](https://www.jetbrains.com/help/idea/http-client-cli.html)), and should be
easy to port to curl. The token to call the API can be obtained via:

```
PRACTITIONER_TOKEN=$(curl -X POST "http://localhost:8090/auth/oauth/token?grant_type=client_credentials" \
     -H "Authorization:Basic $(echo -n 'hmpps-typescript-template-system:clientsecret' | base64)" \
      |  jq -r ".access_token")
      
cat <<EOF > docs/http/http-client.private.env.json
{
  "local": {
    "HOST": "http://localhost:8080",
    "hmpps_auth": {
      "token": "$PRACTITIONER_TOKEN"
    }
  }
}
EOF
```


Adjust the payload (including the CRN, which should match the one in the JSON file, See [Stub NDelius API])

Once an offender has been successfully onboarded, you can create a check-in for them: See [Check-in](http/v2-offender-checkin.http) script.

### UI project: hmpps-probation-checkin-ui .env

In your `.env` put the following

```
CLIENT_CREDS_CLIENT_ID=hmpps-typescript-template-system
CLIENT_CREDS_CLIENT_SECRET=clientsecret
```

### Offender Photo API (FEATURE_2349)

Set `FEATURE_2349=true` to route all reference-photo operations to Offender Photo API.
The current application configuration defaults this flag to `false`; set it explicitly to
`false` to keep the existing S3 client. HTTP failures surface without an S3 fallback.
`OFFENDER_PHOTO_API_URL` defaults to `http://localhost:8085`; outbound authentication uses
the same shared `API_CLIENT_ID` / `API_CLIENT_SECRET` as other API registrations. No separate
photo credentials are needed: `offender-photo-api` is Spring's registration name, not an
Auth client ID. That registration requests `read` and `write` scopes; its Auth client must
allow both and have authority `ESUPERVISION__ESUPERVISION_UI`.
Photo API currently checks that role for all operations, including upload and deletion,
not individual scopes. Requesting `write` does not add scope enforcement.
The photo API fixes upload URL expiry at five minutes, regardless of the client's requested duration.

With the existing Auth, PostgreSQL and LocalStack containers running, start photo-api from its checkout:

```sh
SPRING_PROFILES_ACTIVE=dev S3_DATA_BUCKET_NAME=image-uploads \
  ./gradlew bootRun --args='--hmpps-auth.url=http://localhost:8090/auth'
```

Start esupervision with its local environment, `FEATURE_2349=true` and
`HMPPS_AUTH_URL=http://localhost:8090/auth` (do not use `run-dev.sh`, which selects remote Auth).
Photo API verifies tokens using this same Auth instance's JWKS; it does not need the
caller's client secret. Photo API's `local-aws` profile selects remote Auth unless overridden.
All esupervision API registrations share the Auth provider, so remote dependencies generally
will not accept tokens from local Auth; use local/stub dependencies for those calls.
For containerized services, replace `localhost` with addresses reachable from inside
their containers (for example, Compose Auth at `http://hmpps-auth:8080/auth`).
For a shell-compatible `.env`, the local Auth client documented above can be used as follows:

```sh
set -a
source .env
set +a
export FEATURE_2349=true OFFENDER_PHOTO_API_URL=http://localhost:8085
export API_CLIENT_ID=hmpps-typescript-template-system API_CLIENT_SECRET=clientsecret
export HMPPS_AUTH_URL=http://localhost:8090/auth MANAGE_USERS_API=http://localhost:8090/auth
export APP_ENV=local HOSTED_AT=http://localhost:3000 
export API_BASE_URL=http://localhost:8080
./gradlew bootRun --args='--hmpps-auth.url=http://localhost:8090/auth'
```

That example uses `hmpps-typescript-template-system`; if your existing local shared client
is different, keep its credentials and run the setup script with its client ID instead.
Do not replace the shared credentials just to match the script's default client.

To verify authentication independently of eupervision API, use the configured shared client:

```sh
TOKEN_RESPONSE=$(curl --fail-with-body --silent --show-error \
  --user "$API_CLIENT_ID:$API_CLIENT_SECRET" \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode 'scope=read write' \
  http://localhost:8090/auth/oauth/token) &&
TOKEN=$(printf '%s' "$TOKEN_RESPONSE" | jq -er '.access_token') &&
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8085/v1/offenders/by-uuid/00000000-0000-0000-0000-000000000001/photo/reference/coords
```

The coordinates endpoint does not require an uploaded photo. Then exercise an existing
esupervision API photo endpoint (/v2/offenders/{uuid}/proxy/photo) to verify the WebClient path as well.
A token-endpoint error indicates a client/secret/grant/scope problem; Photo API 401 indicates
token validation or Auth trust problems, while 403 indicates a missing role.
After adding authorities or changing scopes, restart esupervision API to discard cached tokens.
The retry-on-401 filter does not fix a missing-role 403. Keep tokens and secrets out of logs
and committed files.
