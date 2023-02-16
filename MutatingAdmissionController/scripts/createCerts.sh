#!/bin/bash -f
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