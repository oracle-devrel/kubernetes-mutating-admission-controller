#!/bin/bash -f
# create the config maps from the current namespace
# note this should be run from the project home
# delete the existing configs
kubectl delete configmap deploymentmac-config-map --ignore-not-found=true 
kubectl delete secret deploymentmac-secret --ignore-not-found=true 
kubectl create configmap deploymentmac-config-map --from-file=config
kubectl create secret generic deploymentmac-secret --from-file=configsecure