#!/bin/bash -f
kubectl create namespace management
kubectl create namespace mactest
kubectl label namespace mactest mutateme=enabled
kubectl config set-context --current --namespace management