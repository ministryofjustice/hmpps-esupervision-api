#!/bin/bash

# Check arguments
if [ -z "$1" ] || [ -z "$2" ]; then
  echo "Error: Namespace and log-file prefix parameters are required."
  echo "Usage: $0 <namespace> <log-file-prefix>"
  exit 1
fi

NAMESPACE="$1"
FILE_PREFIX="$2"

# Pod name prefix to search for
PREFIX="hmpps-esupervision-api"

# Get all pods matching the prefix in the specified namespace
MATCHING_PODS=$(kubectl -n "hmpps-esupervision-$NAMESPACE" get pods -o=name | grep "$PREFIX" | grep -v "hmpps-esupervision-api-service-pod" | cut -d'/' -f2)

# Check if any matching pods were found
if [ -z "$MATCHING_PODS" ]; then
  echo "No pods matching prefix '$PREFIX' found in namespace '$NAMESPACE'"
  exit 0
fi

# Counter for log files
COUNT=1

# Loop through each matching pod and save logs
for POD in $MATCHING_PODS; do
  echo "Getting logs for pod: $POD in namespace: $NAMESPACE"
  kubectl -n "hmpps-esupervision-$NAMESPACE" logs "$POD" > "${FILE_PREFIX}${COUNT}"
  echo "Saved logs to ${FILE_PREFIX}${COUNT}"
  ((COUNT++))
done

echo "Completed. Saved logs for $(($COUNT-1)) pods."
