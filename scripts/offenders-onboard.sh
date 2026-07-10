#!/bin/zsh

# This script reads in a list of CRNs (one per line)
# and executes a ijhttp script to add those users 
# to esupervision.

SCRIPT_PATH="${0:A:h}/../docs/http/v2-offender-setup-cmd.http"
PRIV_ENV_FILE_PATH="${0:A:h}/../docs/http/http-client.private.env.json"
CRN_LIST_PATH="${1}"

while IFS= read -r crn; do
  # Skip empty lines if any
  [[ -z "$crn" ]] && continue
  
  echo "Processing CRN: $crn"
  ijhttp -p $PRIV_ENV_FILE_PATH -e test -V crn=$crn ${SCRIPT_PATH}
done < "${CRN_LIST_PATH}"
