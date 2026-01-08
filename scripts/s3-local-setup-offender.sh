#!/bin/sh
set -e

IMG_UPLOADS="image-uploads"
OFFENDER_UUID=
OFFENDER_REF_PHOTO=

usage() {
  cat <<EOF
Usage: $0 -u offender-uuid -p offender-ref-photo [-i img-bucket]
  -u    offender UUID (required)
  -p    offender reference photo path (required)
  -i    image uploads bucket (default: ${IMG_UPLOADS})
  -h    show this help
EOF
}

while getopts "i:u:p:h" opt; do
  case "$opt" in
    i) IMG_UPLOADS="$OPTARG" ;;
    u) OFFENDER_UUID="$OPTARG" ;;
    p) OFFENDER_REF_PHOTO="$OPTARG" ;;
    h) usage; exit 0 ;;
    *) usage; exit 1 ;;
  esac
done

if [ -z "${OFFENDER_UUID}" ] || [ -z "${OFFENDER_REF_PHOTO}" ]; then
  echo "Error: offender UUID (-u) and offender reference photo (-p) are required." >&2
  usage
  exit 1
fi

if [ ! -f "${OFFENDER_REF_PHOTO}" ]; then
  echo "Error: offender reference photo file '${OFFENDER_REF_PHOTO}' does not exist." >&2
  exit 1
fi

awslocal s3api put-object \
  --bucket "${IMG_UPLOADS}" \
  --key "setup-${OFFENDER_UUID}" \
  --body "${OFFENDER_REF_PHOTO}" \
  --content-type "image/png"

echo "Uploaded ${OFFENDER_REF_PHOTO} to s3://${IMG_UPLOADS}/setup-${OFFENDER_UUID}"

