#!/bin/bash -f
# Copyright (c) 2023 Oracle and/or its affiliates.
# 
# The Universal Permissive License (UPL), Version 1.0
# 
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or data
# (collectively the "Software"), free of charge and under any and all copyright
# rights in the Software, and any and all patent rights owned or freely
# licensable by each licensor hereunder covering either (i) the unmodified
# Software as contributed to or provided by such licensor, or (ii) the Larger
# Works (as defined below), to deal in both
# 
# (a) the Software, and
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software (each a "Larger Work" to which the Software
# is contributed by such licensors),
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
# 
# This license is subject to the following condition:
# The above copyright notice and either this complete permission notice or at
# a minimum a reference to the UPL must be included in all copies or
# substantial portions of the Software.
# 
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
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