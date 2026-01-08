#!/bin/sh
set -e

IMG_UPLOADS="image-uploads"
VID_UPLOADS="video-uploads"

usage() {
  cat <<EOF
Usage: $0 [-i img-bucket] [-v vid-bucket]
  -i    image uploads bucket (default: ${IMG_UPLOADS})
  -v    video uploads bucket (default: ${VID_UPLOADS})
  -h    show this help
EOF
}

while getopts "i:v:h" opt; do
  case "$opt" in
    i) IMG_UPLOADS="$OPTARG" ;;
    v) VID_UPLOADS="$OPTARG" ;;
    h) usage; exit 0 ;;
    *) usage; exit 1 ;;
  esac
done

awslocal s3 mb "s3://${IMG_UPLOADS}"
awslocal s3 mb "s3://${VID_UPLOADS}"

awslocal s3api put-bucket-cors --bucket "${IMG_UPLOADS}" --cors-configuration file://scripts/cors-config.json
awslocal s3api put-bucket-cors --bucket "${VID_UPLOADS}" --cors-configuration file://scripts/cors-config.json
