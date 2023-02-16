#!/bin/bash -f
# this needs to be rub from the project folder
# create namespaces to work in
echo "Create namespace"
bash scripts/createNamespace.sh
# create the config stuff - this assumes that the certificates have been setup
echo "Create configuration"
bash scripts/createConfig.sh

# now deploy the controller and it's web hooks. This assumes that the webhook has the right CA details
echo "Creating DeploymentMAC deployment"
kubectl apply -f yaml/DeploymentMAC-Deployment.yaml
echo "Creating DeploymentMAC service"
kubectl apply -f yaml/DeploymentMAC-Service.yaml
echo "Creating DeploymentMAC webhook"
kubectl apply -f yaml/DeploymentMAC-Webhook.yaml

echo "Use this command to deploy the basic unannotated ngnix service, this will be in the mactest namespace, which has the label which will trigger the webhook enabled"
echo "kubectl apply -f yaml/NginxBase-Deployment.yaml -n mactest"
echo "the sample targeting the cpuIntensive setting"
echo "kubectl apply -f yaml/NginxAnnotated-CPUIntensive-Deployment.yaml -n mactest"
echo "the sample targeting the memory intensive setting"
echo "kubectl apply -f yaml/NginxAnnotated-MemoryIntensive-Deployment.yaml -n mactest"
echo "the sample targeting a not defined setting (to check it doesn't crash)"
echo "kubectl apply -f yaml/NginxAnnotated-Unknown-Deployment.yaml -n mactest"