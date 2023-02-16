#!/bin/bash
DOCKER_CMD=$HOME/.rd/bin/docker 
IMAGE_NAME=deploymentmac
source ./repoConfig.sh
$DOCKER_CMD build  --tag "$IMAGE_NAME":0.0.1 --tag "$IMAGE_NAME":latest --tag "$REPO"/"$IMAGE_NAME":0.0.1 --tag "$REPO"/"$IMAGE_NAME":latest --file Dockerfile .
$DOCKER_CMD push "$REPO"/"$IMAGE_NAME":latest
$DOCKER_CMD push "$REPO"/"$IMAGE_NAME":0.0.1
echo built and pushed with tags latest and 0.0.1
