#!/bin/bash
source ./repoConfig
DOCKER_CMD=$HOME/.rd/bin/docker 
IMAGE_NAME=nextflowmac
RUNDIR=`pwd`
CONTAINER_DIR=/helidon
echo executing in $RUNDIR
export CONF=$RUNDIR/config
export CONFSECURE=$RUNDIR/configsecure
$DOCKER_CMD run  --publish 8080:8080  --rm  --volume $CONF:/$CONTAINER_DIR/config --volume $CONFSECURE:/$CONTAINER_DIR/configsecure --name $IMAGE_NAME "$IMAGE_NAME":latest