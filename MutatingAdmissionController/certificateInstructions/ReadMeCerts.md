# To create the certs

This is how to create a standalone cert using the small step program.

There are other ways to do this using the Kubernetes certificate system, they are more complex from a demo perspective, but are probably better in a production environment

See this example script on the [Istio project](https://github.com/istio/istio/blob/master/install/kubernetes/webhook-create-signed-cert.sh) for more details on a production style of approach

## Get a certificate tool
Download step from  https://smallstep.com/docs/step-cli

set KEYS_DIR to point to where the certs will be, and STEP_CMD to where the step command is (for mac's it's in the location shown for MacOS Venture at least)

`KEYS_DIR=```pwd```/certificates`

`STEP_CMD=/usr/local/bin/step`

## Create the certificates

### Define the password to use for the keys and keystore

We need to specify the password to use to encrypt the certificates. For ease of use I'm using `deploymentmacpassword` OF course you should NEVER use that in production

`KEY_PASSWORD=deploymentmacpassword`
`echo $KEY_PASSWORD > $KEYS_DIR/password`

For these instructions this will be used for every password, in reality you wouldn't do this but would do use a different password for keay key and key store

### The root certificate authority key / certificate

Create the root cert. When prompted for the password to protect the root private key enter a password, I just used deploymentmacpassword for all of the passwords in the step stages, but you can use whatever you want as long as it's consistent and you remember what you used where

`$STEP_CMD certificate create "Deployment Mac root CA" $KEYS_DIR/root_ca.crt $KEYS_DIR/root_ca.key --profile root-ca --insecure --kty=RSA --password-file=$KEYS_DIR/password`

### The intermediate certificate authority key / certificate

Now create the intermediate CA (this is just good practice on managing your certificates and protecting the root, you can of course just create TLS certs from the root)
You may be prompted to enter the encryption for the root CA key and then for the intermediate key, I used deploymentmacpassword for both, you use what you want 

`$STEP_CMD certificate create "Deployment Mac Intermediate CA" $KEYS_DIR/intermediate_ca.crt $KEYS_DIR/intermediate_ca.key  --profile intermediate-ca --ca $KEYS_DIR/root_ca.crt --ca-key $KEYS_DIR/root_ca.key  --password-file=$KEYS_DIR/password  --ca-password-file=$KEYS_DIR/password`

If you created a proper root you can now move that to offline storage if following good security practice

### The service key / certificate pair

Create the signing key for the service itself, note that we are looking here to use the within K8S DNS name, so this implies the code will be running in the management namespace and the service called deploymentmac

You will be prompted to enter the encryption for the intermediate CA key and then for the "leaf" key, I used deploymentmacpassword for both, you use what you want 

Important - DNS names in the cluster seem to be service name.namepace name.svc the .cluster.local is not used when calling a mutating webhook

`$STEP_CMD certificate create deploymentmac.management.svc $KEYS_DIR/deploymentmac.crt $KEYS_DIR/deploymentmac.key --profile leaf --not-after=8760h --ca $KEYS_DIR/intermediate_ca.crt --ca-key $KEYS_DIR/intermediate_ca.key --bundle  --password-file=$KEYS_DIR/password  --ca-password-file=$KEYS_DIR/password`

### Validate the certificates

Validate the certs - if you don't get any messages this means that it passed the verification

`$STEP_CMD certificate verify $KEYS_DIR/deploymentmac.crt --roots $KEYS_DIR/root_ca.crt`

## Packaging the certificates

### Into a p12 keystore

package the certificates and the key into a p12 keystore, you'll need to enter a password for the keystore and also to unlock the private key as before I used deploymentmacpassword for the password

`$STEP_CMD certificate p12 $KEYS_DIR/deploymentmac.p12  $KEYS_DIR/deploymentmac.crt $KEYS_DIR/deploymentmac.key --ca $KEYS_DIR/intermediate_ca.crt --ca $KEYS_DIR/root_ca.crt   --password=file=$KEYS_DIR/password`

If you want and have openssql installed you can examine the contents of the keystore, you will have to enter the password for the keystore file and also for the private key

`openssl pkcs12 -in $KEYS_DIR/deploymentmac.p12 -info`

### Into a p8 format

If you want you can also convert the key file into a PKCS8 format both step and openssl have the tools to do that, however the p12 format is probabaly easier to use
  
### Into the CA Bundle format used by Kubernetes

This is basically the certificate in a base64 format

In this case we're packaging the root certificate, we can get the info using this command

`cat $KEYS_DIR/root_ca.crt | base64`

The resulting output can then be used as the CA_BUINDLE

`CA_BUNDLE=``cat $KEYS_DIR/root_ca.crt | base64``

But here we're going to put it into an environment variable for use when we make use of the templates later

## Moving the p12 file

The p12 file has been created in the same place as all of the keys and certificates. We don't want those other items to be added to the kubernetes environment so we're going to copy it from the working directory where we created everything into the directory that will contain the security info loaded into the kubernetes secret.

`cp $KEYS_DIR/deploymentmac.p12 configsecure/deploymentmac.p12`

## Configuring the templates

The templates are used to reduce the need to manually edit files with the passwords, this also means that this can be automated.

To do these commands you must have set the CA_BUNDLE and KEY_PASSWORD environment variables as above.

The servicetls template goes into the configsecure directory

`cat templates/servertls-template.yaml | envsubst > configsecure/servertls.yaml`

The webhook template contains info on how the mutating admission controller framework talks to the controller we have developed

`cat templates/DeploymentMAC-Webhook-template.yaml | envsubst > yaml/DeploymentMAC-Webhook.yaml`

## Disclaimer

ORACLE AND ITS AFFILIATES DO NOT PROVIDE ANY WARRANTY WHATSOEVER, EXPRESS OR IMPLIED, FOR ANY SOFTWARE, MATERIAL OR CONTENT OF ANY KIND

## Copyright

Copyright (c) 2023 Oracle and/or its affiliates. All rights reserved.
The Universal Permissive License (UPL), Version 1.0