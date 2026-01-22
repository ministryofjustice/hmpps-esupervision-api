# Local Development

The application relies on a number external services (e.g. NDelius, AWS Rekognition) to function. 
But it's possible to configure it to use stubbed or containerised versions of them.

Localstack is used for stubbing AWS services (execept AWS Rekognition). For other's please read on.

### HMPPS Auth (in a container)

You should have the service running in a container.

Log in at http://localhost:8090/auth/ui

In `hmpps-typescript-template` client, under "Authorities" add the following roles:
- ESUPERVISION__PRACTITIONER__RW

In `hmpps-typescript-template-system` client, under "Authorities" add the following roles:
- ESUPERVISION__ESUPERVISION_UI

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

Add `stubndilius` Spring profile - replaces Ndilius client with one generating data based on the CRN in `src/test/resources/ndilius-responses/default.json`

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
