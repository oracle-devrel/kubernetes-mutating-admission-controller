#!/bin/bash -f
if [ -z "$KEYS_DIR" ]
then
  echo "The variable KEYS_DIR has not been set, cannot continue"
  exit 1
fi
if [ ! -d "$KEYS_DIR" ]
then
  echo "directory $KEYS_DIR defined in the variable KEYS_DIR does not exist. This is the directory the certificates are created in."
fi
echo "Removing all certs data"
rm $KEYS_DIR/*.key $KEYS_DIR/*.crt $KEYS_DIR/*.p12 $KEYS_DIR/password

echo "Removing keystore from source directory for Kubernetes secret"
rm configsecure/deploymentmac.p12

echo "Removing the templated webhook and server tls config files"
rm yaml/DeploymentMAC-Webhook.yaml configsecure/servertls.yaml
