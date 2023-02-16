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
if [ -z "$STEP_CMD" ]
then
  echo "The variable STEP_CMD has not been set, cannot continue"
  exit 1
fi
if [ -x "$STEP_CMD" ]
then
    echo "Located step"
else
    echo "Step cmd '$STEP_CMD' set in ther veriabla STEP_CMD is not executable or found"
    exit 2
fi

if [ -z "$KEYS_DIR" ]
then
  echo "The variable KEYS_DIR has not been set, cannot continue"
  exit 1
fi
if [ ! -d "$KEYS_DIR" ]
then
  echo "directory $KEYS_DIR defined in the variable KEYS_DIR does not exist. This is the directory the certificates are created in."
fi

if [ -z "$(ls -A $KEY_DIR)" ]
then
   echo "$KEYS_DIR contains files, will not continue, remove these if you want to create new certs, keys and key store"
   exit 3
else
   echo "No existing files in $KEYS_DIR, continuing"
fi

KEY_PASSWORD=deploymentmacpassword
export KEY_PASSWORD
echo $KEY_PASSWORD >> $KEYS_DIR/password

SERVICE_NAME=deploymentmac
SERVICE_NAMESPACE=management
export SERVICE_NAME
export SERVICE_NAMESPACE

echo "Will use $KEY_PASSWORD for all key and key store passwords, this is OK for a demo, but very bad practice in a produciton situation"

echo "Creating root CA"
$STEP_CMD certificate create "Deployment Mac root CA" $KEYS_DIR/root_ca.crt $KEYS_DIR/root_ca.key --profile root-ca --insecure --kty=RSA --password-file=$KEYS_DIR/password

echo "Creating intermediate CA"
$STEP_CMD certificate create "Deployment Mac Intermediate CA" $KEYS_DIR/intermediate_ca.crt $KEYS_DIR/intermediate_ca.key  --profile intermediate-ca --ca $KEYS_DIR/root_ca.crt --ca-key $KEYS_DIR/root_ca.key  --password-file=$KEYS_DIR/password  --ca-password-file=$KEYS_DIR/password

echo "Creating service certificate"
$STEP_CMD certificate create "$SERVICE_NAME"."$SERVICE_NAMESPACE".svc $KEYS_DIR/deploymentmac.crt $KEYS_DIR/deploymentmac.key --profile leaf --not-after=8760h --ca $KEYS_DIR/intermediate_ca.crt --ca-key $KEYS_DIR/intermediate_ca.key --bundle  --password-file=$KEYS_DIR/password  --ca-password-file=$KEYS_DIR/password

echo "Packaging into p12 keystore, you will need to enter $KEY_PASSWORD when prompted for the key password"
$STEP_CMD certificate p12 $KEYS_DIR/deploymentmac.p12  $KEYS_DIR/deploymentmac.crt $KEYS_DIR/deploymentmac.key --ca $KEYS_DIR/intermediate_ca.crt --ca $KEYS_DIR/root_ca.crt --password-file=$KEYS_DIR/password

echo "Copying keystore to source directory for Kubernetes secret"
cp $KEYS_DIR/deploymentmac.p12 configsecure/deploymentmac.p12

echo "The following is the string to use in the Mutating Webhook as the caBundle field, this is a single line"

CA_BUNDLE=`cat $KEYS_DIR/root_ca.crt | base64`
echo $CA_BUNDLE
export CA_BUNDLE

echo "Applying the CA Bundle and key password to the template configuration files and copying them to their locations"

echo "Server tls configuration"
`cat templates/servertls-template.yaml | envsubst > configsecure/servertls.yaml`

echo "Webhook configuration"
cat templates/DeploymentMAC-Webhook-template.yaml | envsubst > yaml/DeploymentMAC-Webhook.yaml