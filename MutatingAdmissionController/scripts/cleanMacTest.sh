#!/bin/bash -f
# have to de-register the webhook first otherwise the mutating webhook controller will still have references to it and will fail admissions
kubectl delete -f yaml/DeploymentMAC-Webhook.yaml
kubectl delete namespace mactest
kubectl delete namespace management