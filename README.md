# hmpps-esupervision-api

[![repo standards badge](https://img.shields.io/badge/endpoint.svg?&style=flat&logo=github&url=https%3A%2F%2Foperations-engineering-reports.cloud-platform.service.justice.gov.uk%2Fapi%2Fv1%2Fcompliant_public_repositories%2Fhmpps-esupervision-api)](https://operations-engineering-reports.cloud-platform.service.justice.gov.uk/public-report/hmpps-template-kotlin "Link to report")
[![Docker Repository on ghcr](https://img.shields.io/badge/ghcr.io-repository-2496ED.svg?logo=docker)](https://ghcr.io/ministryofjustice/hmpps-esupervision-api)
[![API docs](https://img.shields.io/badge/API_docs_-view-85EA2D.svg?logo=swagger)](https://hmpps-template-kotlin-dev.hmpps.service.justice.gov.uk/webjars/swagger-ui/index.html?configUrl=/v3/api-docs)

The eSupervision API provides backend services for remote probation check-ins, enabling offenders to complete video check-ins and practitioners to review submissions.

## V2 Architecture

The API has been refactored to **V2** with significant architectural improvements:

| Feature | Description |
|---------|-------------|
| **No PII Storage** | Personal data fetched on-demand from Ndilius (source of truth) |
| **Complete Isolation** | V2 code in separate `v2` package with `_v2` database tables |
| **Domain Events** | Full integration with AWS SQS for event publishing to Ndilius |
| **Performance** | Batch operations, fetch joins, lazy loading for S3 URLs |

### Documentation

| Document | Description |
|----------|-------------|
| **[V2 Overview](docs/v2/README.md)** | Architecture overview and package structure |
| **[User Journeys](docs/v2/USER_JOURNEYS.md)** | Setup, checkin, and review flows |
| **[Domain Events](docs/v2/DOMAIN_EVENTS.md)** | Event publishing and Ndilius integration |
| **[Notifications](docs/v2/NOTIFICATIONS.md)** | SMS/Email notification system |
| **[Background Jobs](docs/v2/BACKGROUND_JOBS.md)** | Scheduled jobs |
| **[Data Model](docs/v2/DATA_MODEL.md)** | Entity relationships and schema |
| **[Implementation Notes](docs/v2/IMPLEMENTATION_NOTES.md)** | UI changes, tests, GDS compliance |
| **[V1→V2 Migration](docs/V1_TO_V2_MIGRATION.md)** | Data migration guide |

### Key V2 Endpoints

| Category | Base Path | Description |
|----------|-----------|-------------|
| Setup | `/v2/offender_setup` | Offender registration flow |
| Checkins | `/v2/offender_checkins` | Check-in CRUD and lifecycle |
| Events | `/v2/events` | Domain event callback endpoints for Ndilius |

### V2 Flows Overview

```mermaid
flowchart LR
    subgraph Setup
        A[Practitioner] -->|Register Offender| B[/v2/offender_setup]
        B -->|Upload Photo| C[S3]
    end

    subgraph Checkin
        D[Offender] -->|Complete Checkin| E[/v2/offender_checkins]
        E -->|Verify Face| F[Rekognition]
        E -->|Upload Video| C
    end

    subgraph Review
        G[Practitioner] -->|Review| E
    end

    subgraph Events
        E -->|Publish| H[SQS]
        H -->|Callback| I[Ndilius]
        I -->|Get Details| J[/v2/events]
    end
```

# Instructions

If this is a HMPPS project then the project will be created as part of bootstrapping -
see [dps-project-bootstrap](https://github.com/ministryofjustice/dps-project-bootstrap). You are able to specify a
template application using the `github_template_repo` attribute to clone without the need to manually do this yourself
within GitHub.

This project is community managed by the mojdt `#kotlin-dev` slack channel.
Please raise any questions or queries there. Contributions welcome!

Our security policy is located [here](https://github.com/ministryofjustice/hmpps-template-kotlin/security/policy).

Documentation to create new service is located [here](https://tech-docs.hmpps.service.justice.gov.uk/applicationplatform/newservice-GHA/).

## Creating a Cloud Platform namespace

When deploying to a new namespace, you may wish to use the
[templates project namespace](https://github.com/ministryofjustice/cloud-platform-environments/tree/main/namespaces/live.cloud-platform.service.justice.gov.uk/hmpps-templates-dev)
as the basis for your new namespace. This namespace contains both the kotlin and typescript template projects, 
which is the usual way that projects are setup.

Copy this folder and update all the existing namespace references to correspond to the environment to which you're deploying.

If you only need the kotlin configuration then remove all typescript references and remove the elasticache configuration. 

To ensure the correct github teams can approve releases, you will need to make changes to the configuration in `resources/service-account-github` where the appropriate team names will need to be added (based on [lines 98-100](https://github.com/ministryofjustice/cloud-platform-environments/blob/main/namespaces/live.cloud-platform.service.justice.gov.uk/hmpps-templates-dev/resources/serviceaccount-github.tf#L98) and the reference appended to the teams list below [line 112](https://github.com/ministryofjustice/cloud-platform-environments/blob/main/namespaces/live.cloud-platform.service.justice.gov.uk/hmpps-templates-dev/resources/serviceaccount-github.tf#L112)). Note: hmpps-sre is in this list to assist with deployment issues.

Submit a PR to the Cloud Platform team in
#ask-cloud-platform. Further instructions from the Cloud Platform team can be found in
the [Cloud Platform User Guide](https://user-guide.cloud-platform.service.justice.gov.uk/#cloud-platform-user-guide)

## Renaming from HMPPS Template Kotlin - github Actions

Once the new repository is deployed. Navigate to the repository in github, and select the `Actions` tab.
Click the link to `Enable Actions on this repository`.

Find the Action workflow named: `rename-project-create-pr` and click `Run workflow`. This workflow will
execute the `rename-project.bash` and create Pull Request for you to review. Review the PR and merge.

Note: ideally this workflow would run automatically however due to a recent change github Actions are not
enabled by default on newly created repos. There is no way to enable Actions other then to click the button in the UI.
If this situation changes we will update this project so that the workflow is triggered during the bootstrap project.
Further reading: <https://github.community/t/workflow-isnt-enabled-in-repos-generated-from-template/136421>

The script takes six arguments:

### New project name

This should start with `hmpps-` e.g. `hmpps-prison-visits` so that it can be easily distinguished in github from
other departments projects. Try to avoid using abbreviations so that others can understand easily what your project is.

### Slack channel for release notifications

By default, release notifications are only enabled for production. The circleci configuration can be amended to send
release notifications for deployments to other environments if required. Note that if the configuration is amended,
the slack channel should then be amended to your own team's channel as `dps-releases` is strictly for production release
notifications. If the slack channel is set to something other than `dps-releases`, production release notifications
will still automatically go to `dps-releases` as well. This is configured by `releases-slack-channel` in
`.circleci/config.yml`.

### Slack channel for pipeline security notifications

Ths channel should be specific to your team and is for daily / weekly security scanning job results. It is your team's
responsibility to keep up-to-date with security issues and update your application so that these jobs pass. You will
only be notified if the jobs fail. The scan results can always be found in circleci for your project. This is
configured by `alerts-slack-channel` in `.circleci/config.yml`.

### Non production kubernetes alerts

By default Prometheus alerts are created in the application namespaces to monitor your application e.g. if your
application is crash looping, there are a significant number of errors from the ingress. Since Prometheus runs in
cloud platform AlertManager needs to be setup first with your channel. Please see
[Create your own custom alerts](https://user-guide.cloud-platform.service.justice.gov.uk/documentation/monitoring-an-app/how-to-create-alarms.html)
in the Cloud Platform user guide. Once that is setup then the `custom severity label` can be used for
`alertSeverity` in the `helm_deploy/values-*.yaml` configuration.

Normally it is worth setting up two separate labels and therefore two separate slack channels - one for your production
alerts and one for your non-production alerts. Using the same channel can mean that production alerts are sometimes
lost within non-production issues.

### Production kubernetes alerts

This is the severity label for production, determined by the `custom severity label`. See the above
#non-production-kubernetes-alerts for more information. This is configured in `helm_deploy/values-prod.yaml`.

### Product ID

This is so that we can link a component to a product and thus provide team and product information in the Developer
Portal. Refer to the developer portal at https://developer-portal.hmpps.service.justice.gov.uk/products to find your
product id. This is configured in `helm_deploy/<project_name>/values.yaml`.

## Manually branding from template app

Run the `rename-project.bash` without any arguments. This will prompt for the six required parameters and create a PR.
The script requires a recent version of `bash` to be installed, as well as GNU `sed` in the path.

## Common Kotlin patterns

Many patterns have evolved for HMPPS Kotlin applications. Using these patterns provides consistency across our suite of 
Kotlin microservices and allows you to concentrate on building  your business needs rather than reinventing the 
technical approach.

Documentation for these patterns can be found in the [HMPPS tech docs](https://tech-docs.hmpps.service.justice.gov.uk/common-kotlin-patterns/). 
If this documentation is incorrect or needs improving please report to [#ask-prisons-digital-sre](https://moj.enterprise.slack.com/archives/C06MWP0UKDE)
or [raise a PR](https://github.com/ministryofjustice/hmpps-tech-docs). 

## Running the application locally

The application comes with a `dev` spring profile that includes default settings for running locally. This is not
necessary when deploying to kubernetes as these values are included in the helm configuration templates -
e.g. `values-dev.yaml`. You can also specify an additional `stubrekog` profile when running the application
to not use Rekognition, andy rely on a stub implementation of the face comparison service.

There is also a `docker-compose.yml` that can be used to run a local instance of the template in docker and also an
instance of HMPPS Auth (required if your service calls out to other services using a token).

### Rekognition

The deployed API uses [AWS Rekognition](https://aws.amazon.com/rekognition/) to verify user identities. During development you can configure a stub 
service which automatically passes the identity match, or you can configure AWS credentials to use the real service.
The two approaches are described below:

#### AWS credentials

Access to Rekognition is
granted to a role configured within the Modernisation Platform. To configure access to this role locally you will need
the following:

* The ARN of the Rekognition role within the development modernisation platform
* Credentials for accessing AWS
* The name of the S3 data bucket used to store Rekognition images

Login to the AWS console using the instructions in [this guide](https://user-guide.modernisation-platform.service.justice.gov.uk/user-guide/getting-aws-credentials.html#getting-aws-credentials).
The role can be found in the development console under IAM -> Roles -> rekognition-role. Configure the `REKOG_ROLE_ARN` setting in your local `.env` file with the ARN of the role (this should look like
`arn:aws:iam::${ACCOUNT_ID}:role/rekogition-role`).

From the same page you can obtain temporary credentials from AWS. Click `Access Keys` and copy the settings from `Option 1: Set AWS environment variables` into your local `.env` file (or configure them
in your environment some other way). These should be the `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` variables.

Finally, the name of the S3 data bucket can be found in the AWS console, use this to configure the `REKOG_S3_DATA_BUCKET` setting in your `.env` file.

#### Stub identity service

If you include the `stubrekog` alongside the `dev` profile when running the API e.g. `SPRING_PROFILES_ACTIVE=dev,stubrekog ./gradlew bootRun` then a stub verification service will be configured which always succeeds. 
Identity image snapshots will be uploaded to an S3 bucket in localstack. Create this local bucket and configure the CORS policy (see [here](#localstack) for the policy document to apply):

```shell
awslocal s3api create-bucket --bucket hmpps-esupervision-rekognition-uploads
awslocal s3api put-bucket-cors --bucket hmpps-esupervision-rekognition-uploads --cors-configuration file://cors-config.json
```

configure the uploads bucket in the environment when running the API:

    REKOG_S3_DATA_BUCKET=hmpps-esupervision-rekogntion-uploads


### Postgres

The docker-compose configuration defines a local postgres instance used for persistence. Before running services locally,
define a `.env` file containing the password for the default `postgres` user:

__.env__
```properties
POSTGRES_PASSWORD=your_local_dev_password
```

note this file is also loaded within the Spring boot `application-dev.yml` file so the password does not need to be repeated
there.

### Gov.uk Notify

You should also configure an API key for the Gov.UK [notify service](https://www.notifications.service.gov.uk/). There is a test
key in the `hmpps-esupervision-notify-api-key-test` secret within the development namespace which you can use locally. Set the
`NOTIFY_API_KEY` environment variable when running the service or in your local `.env` file:

```properties
NOTIFY_API_KEY=notifykey
```

This key does not send real messages but will appear in the notify dashboard.

### Localstack

The application needs a number of S3 buckets to function. To allow clients (browsers) to upload 
files to the bucket (using a pre-signed URLs), we need to apply the following CORS configuration.

```json
{
  "CORSRules": [
    {
      "AllowedHeaders": ["*"],
      "AllowedMethods": ["GET", "PUT"],
      "AllowedOrigins": ["*"],
      "ExposeHeaders": ["ETag"],
      "MaxAgeSeconds": 3000
    }
  ]
}
```

To apply it to your localstack instance, put the above into a file `cors-config.json` and run:

```sh
awslocal s3api put-bucket-cors --bucket hmpps-esupervision-video-uploads --cors-configuration file://cors-config.json
awslocal s3api put-bucket-cors --bucket hmpps-esupervision-image-uploads --cors-configuration file://cors-config.json
```


Run services with:

```bash
docker compose pull && docker compose up
```

will build the application and run it and HMPPS Auth within a local docker instance.

### Running the application in Intellij

```bash
docker compose pull && docker compose up --scale hmpps-esupervision-api=0
```

will just start a docker instance of HMPPS Auth. The application should then be started with a `dev` active profile
in Intellij.
