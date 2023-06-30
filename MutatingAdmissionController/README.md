# Deployment Mutating Admission Controller

## What this is

A project designed to let work with the Kubernetes Mutating Admission Controller webhook mechanism to add settings to your deployments on the fly based on a configuration tree and a label in the deployment.

The underlying use case is to apply things like resource limits and node selectors based on a single incoming label, this effectively allows you to separate the resource management from the deployment definition and apply the resource controls at deployment time. Though the initial use case related ro resource controls the code is actually general purpose in that it can add or remove settings 

You can define part of the configuration on a tree basis (e.e. the resources) that will be added to the deployment if not present, and also on the individual fields e.g. resources-> limit -> cpu which can be replaced if they already exist. You can also process arrays (e.g the containers array) by searching on a key / value pair within the array or the array index (searching on keys is probably better as it allows for order changes from other modifying admission controllers which may for example add a startup container)

## What this is not

A general modification mechanism, for example there is no conditional support.

## Build and run

This code uses Maven, which is great as it basically handles pretty much everything for you including all of the dependency downloads. You can use all of your favorite mvn commands to build and package the code.

As this was also build using the Helidon kick starter there are also docker files to build containers (the default Dockerfile) a limited size image using jlink (Dockerfile.jlink) and also a native image using GraalVM (Dockerfile.native)

## Comments on mutating admission controllers and webhooks

The idea of a mutating admission controller is it can modify the Kubernetes objects as they are being processed see the [Kubernetes mutating admission controller documentation](https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/) Rather than having to deploy admission controllers into the Kubernetes infrastructure itself Kubernetes supports a webhook mechanism that allows you to define a Kubernetes services that reads the incoming object and generates a set of changes (handled as a JSON Patch) that will be applied to the object. Of course you need to be exceptionally careful here as this is sitting in the cluster management space, so if your code goes wrong or the webhook had configuration problems then you can easily stop anything happening in your cluster, even to the extent of being unable to replace your broken admission controller service (this is from personal experience !) My very strong advice is if at all possible have the admission controller service run in a namespace that is not itself subject to the controls of the admission controller, that way you can at least remove the controller and replace it. One way to do this is to use namespace labels or annotations and to only look at the namespaces where you have deliberately enabled the controller. 

## This is a service right ?

Absolutely, that means that you will need a service definition, and as the mutating admission controller will be part of your management path it's a good idea to have multiple replicas and also a rollout strategy that ensure that at least one replica is running at all times.

Additionally if using the cluster autoscaler ensure that you configure it to make sure that it can't remove all of the nodes the controller is running on. For OKE you can create a node pool for management functions and not use the auto scaler on that, then label the nodes in that node pool 

Of course at this is an **internal** service that is only used by the cluster itself then you can just use a cluster ip and core dns to access it, you won't need an external load balancer (though this can cause some security complexities)

## Scripts and automation

### Creating certificates, keys and key stores

You can set a bunch of stuff up using scripts if you just want to jump in. Make sure that [small step](https://smallstep.com/) is installed and that the shell variable STEP_CMD points to it, then set the KEYS_DIR environment variable to point to the working directory for creating the keys (this directory must already exist) You can edit the `scripts/createCerts.sh` and set KEY_PASSWORD if you don't want to use `deploymentmacpassword` as the password for the certificates (you should definately do this for any kind of public facing or production system

From the project level directory run :

  `bash scripts/createCerts.sh`
  
You will be asked for the password at one point (unfortunately I can't find the right flag to pass it in when creating the p12 file) so when prompted use the password  you defined above (or `deploymentmacpassword` if you didn't change it)

This script will also take the template webhooks and server tls configuration files and update them with the required values, for example placing a base64 encoded version of the certificate root in the web hook CA Bundle

### Installing the configuration and services

You will need a test Kubernetes cluster (don't run these scritps in a production cluster as they reset namespaces) and to have that as the default context in your kubeconfig file. You will also need to have built and pushed the container image and updated the yaml/DeplymentMAC-Deployment.yaml with the container image location and if needed added an imagePullSecret

From the project level directory run :

  `bash scripts/setupMacTest.sh`

This will delete any existing `management` and `mactest` namespaces, create new ones and set the `management` namespace as the default. It will create the config maps and secrets, then the service and deployment objects. Finally it will install the webhook and give you some example commands to play with

## Security on your webhook

For security Kubernetes **required** that the services that deliver the mutating admission  controller webhooks must be secured using SSL and that you specify the certificate to be used in the webhook. This can be the Kubernetes cluster certificate or one that you define, one thing that all of the documentation I saw seems to not make clear is that when you define the Certificate authority bundle for the webhook definition you must use a base64 encoded version of the certificate chain.

As the service behind the webhook is only used within the cluster this means that you can't rely on an external LB to do the TLS / SSL termination, in th case of this code I just use the SSL / TLS capabilities built into Helidon and have the servcie itself do the termination.

Note that this creates a certificate assuming the service name is `deploymentmac` running in the `management` namespace, if not you'll need to change the service name inthe last cetificate creation stage.

As you are specifying the Certificate Authority bundle to use as part of the webhook this means that you **can** use a self signed certificate if you want to, it's up to you how to create this, but I have been using [small step](https://smallstep.com/)  and the docs and script here use it, but OpenSSL would work fine (I just find that small step has fewer steps to go through which foir test / demos / labs is a lot easier)

Of course I use Kubernetes secrets to hold the private key and if your Kubernetes implementation supports it I would recommend using some form of vault service like ExternalSecrets combined with OCI Vault to protect your secrets

The `template/servertls-template.yaml` file is a sample of the Helidon configuration, this should be edited to se the .12 file location and password then copied to `configsecure/servertls.yaml` folder along with the p12 file containing the private key of the service and certificate chain.

## Running the code

To run this code you need to compile and package the code into a container image and add it to a registry, you will need to have done a docker login on the registry then you can manually compile and push or use the `buildPustToRepo.sh` script (edit the `repoCoinfig.sh` file to specify the repo location first) 

The yaml folder contains the Service, Deployment objects, copy copy the `templates/DeploymentMAC-Webhook-template.yaml` to `yaml/DeploymentMAC-Webhook.yaml` and then edit the  file and set the image location (and add an imagePullSecret if needed) the DeploymentMAC-Service.yaml file should not need any changes (note that you should use port 44s for the external service port as multiple Kubernetes managed services seem to require that port (and not allow the webhook definition to override it).

The webhook definition has a number of things you may need to change, the service name is assumed to be `deploymentmac` running in the `management` namespace. You will also need to get the CA Bundle file as base64 text - the `createCerts.sh` script will update these for you along with the `servertls.sh` but if runing by hand you'll need to fix that

This code was written using the Helidon framework to handle all of the REST requests, it also uses Helidon to handle the configuration of not only the Helidon aspects (e.g. port, certificates etc.) but also the input to the mutating process.


Configuration settings can of course be defined in any of the configuration sources used by the Helidon configuration system, including the yaml files below. However it is expected that the configurations below would be a good place to start. If you want to use your own structure then Helidon does load the meta-config.yaml configuration option which will allow you to specify your own config files locations.  

Note that version 1.0.0 only supported the `./configsecure/servertls.yaml` and `config/mappingconfig.yaml` files. The structure was changed and the `config/mappingconfig.yaml` file is no longer loaded. 

The configs used are below: (All relative to the application run directory)

  ```
     ./configsecure
       <p12 keystore file>
       servertls.yaml
     ./config
       mutationconfig.yaml
     ./mappings
       mappings.yaml
     ./substitutions
       substitutions.yaml
  ```
  
The configuration is loaded in the following order `servertls.yaml, mutationconfig.yaml, mappings.yaml, substitutions.yaml`

The `p12 keystore file` contains the private key for the services and the certificate chain. There is an example file or the `templates` directory has a template used by the scripts to setup certificates that will be created and configured for you

the `servertls.yaml` file contains the configuration of the Helidon server, it is required. Amongst other things this will define the location of the keystore (the `<p12 keystore file>` and the password used to decrypt it (the certs setup script will configure this for you based on the template in the templates directory)

The `mutationconfig.yaml` file is the actual set of controls for the mapping process, and the various maps to be applied. More on this file later. This is required.

The `mappings.yaml` file are the mappings to be applied. More on this file later. This is optional.

The `substitutions.yaml` file are the substitutions to be applied. More on this file later. This is optional.

When using Kubernetes the configsecure and config directories need to be put into Kubernetes secrets and config maps which will be used as sources for volumes and mounted into the container at the locations above.


### The main mutator configuration

The `mutationconfig.yaml` file defines how the mappings will be used. It has the following structure

```yaml
mutationEngine:
  # a comman separated list of namespces to review when accepting inputs. If the object namespace is not on this list then 
  # nothing will be modified
  targetNamespaces: "mactest"
  input:
    mappings:
      # do we run the mappings processs ?
      doMappings: "true"
      # name of the metadata label from the input that will contain the name of the mappings
      labelName: "mutatorMapping"
      # is a mapping required to be present ?
      requireMapping: "false"
      # do we error if we can't find a mapping ?
      errorOnMissingMapping: "true"
    # These control the substitutions engine
    substitutions:
      # do we look for substitutions in the incoming JSON or the replacement settings ?
      # defaults to false (Only applied to string values)
      doSubstitutions: "true"
      # indicates tha start of a substitution placeholder defaults to {{
      substitutionStart: "{{"
      # indicates tha end of a substitution placeholder defaults to }}
      substitutionEnd: "}}"
```

The structure is as follows :

`mutate:` - indicates that this is the controls for the mutating code

`  targetNamespaces: "list,of,comma,separated,namespaces,to,consider,deployments,to,modify"` - A JSON string with the namespaces you want to perform mutations in, ths code will break these down using the `,` as a separator (and remove leading / trailing whitespace) When Kubernetes passes this request to the code the namespace is checked and any deployments with a namespace not in the list will be approved without any changes. Note that this is separate form any restrictions in namespaces you may have in place on the webhook definition. Also you are STRONGLY advised to exclude the namespace the controller itself is running in, that way any problems with the code or configuration will not prevent you from deploying a new version
 
 `  input:` - This section defines what the code will look at to determine if it needs to modify the deployment, and what to do if it can'd find a mapping or there is no mapping specified.
 
 `      doMappings:` If true will modify inbound JSON with the details in the mappings, either replacing existing content or adding new content. If Substitutions are also active (doSubstitutions) then any placeholders in the mappings will be substituted, this means that for example that you could insert an additional container which is based on an image who'se container registry is updated based on the region the program is running in (or whatever is in the substitutions map). 
 
 `      labelName:` - This contains the name of the label in the deployments metadata / label that indicates what mappings to apply. So if the value was `macMapping` then the code will look into the deployments metadata / label for a key named of macMapping, the value that key contains will be used to locate a mapping section (see later for those). If the incoming deployment does have a metadata / labels section, or the key does not exist in that section then if requireMapping is false the request will be approved with no changes, if not will be rejected. Defaults to `targetMapping`
 
 `      requireMapping:` - If true then missing the metadata / label section or not having the label as defined in labelName will cause the deployment to be rejected, if false it will be accepted. Basically this can be used to force all deployment requests to the namespaces listed to go through the mapping process.
 
 `      errorOnMissingMapping:` if the deployment requests a mapping that does not exist and this is true then throw an error and block it, if false and we can;t locate a mapping then let it continue unchanged. Defaults to true
 
 `    substitutions:` This section controls the substitution engine, See the substitution configuration below for details of how to specifi substitutions
 
 `      doSubstitutions:` If true and if mappings are operational then the substitution engine will look for substitutions in the mappings specified (see below) of if there isn't a substitution in the mappings for a JSON string field in the incoming deployment. 
 
 `      substitutionStart:` it present a string indicating the start of a substitution placeholder, defaults to `{{` if not set
 
 `      substitutionEnd:` it present a string indicating the end of a substitution placeholder, defaults to `}}` if not set Note that this can be the same character sequence as the substitutionStart of desired as the engine looks for the the end sequence after the start.

### The mapping configuration

For ease of optional file `mappings/mappings.yaml` is looked for by the configuration engine and is loaded if present. The reason for having separate files is that it will let you easily use Kubernetes configuration mechanisms (the various things like PVC's, config maps or secrets) to change the mapping structure options without having to modify the core configuration.

The `mappings.yaml` file defines how the mappings will be used. It has the following structure

```yaml
# the mutator looks for the label specified in the mutate.input.labelName config 
# setting. When presented with incomming deployments the mutator looks for that 
# label and treats it's value as the mappings to apply to the pods that match. This 
# is then run, so for example if mutate.input.labelName was set to mutatorMapping
# and the incoming deployment had a label mutatorMapping with the value cpuIntensive
# then this first set of mappings would be the ones applied to that deployment
mappings:
  cpuIntensive : 
    spec:
      template:
        spec:
          containers:
          # is required, can be either key or index (case insensitive) 
          # if key then you need a arrayKey, arrayKeyValue and arrayValue config entry and optionally arrayKeyMissingError and arrayKeyValueNoMatchAction
          # if index then you need an arrayIndex with the value indicating the array oindex to use in the json and optionally arrayIndexMissingError
          # Note that if you are adding items to the array using an index then arrayKey and arrayKeyValue are also required.
          - arrayElementConfigType: "key"
            arrayKey: "name"
            # this is optional, if the key can't be found then you have the option to error and reject the request, if false then this is just ignored
            arrayKeyMissingError: false 
            arrayKeyValue: "nginx"
            # this is optional, if there is no item in the JSON from Kubernetes in the list which has a key matching the keyValue then you have the 
            # choice to ignore this config item totally (default), add a new item to the list (at the end) or error which will reject the entire request.
            # If yuou chose to add then the code will automatically add an entry to the JSON generated from arrayValue which will have a name of the 
            # arrayKey value and a value of the arrayKeyValue
            # Note that if arrayKeyMissingError is false then this no match action will kick in if there are no objects that have a field with the arrayKey. This 
            # means that if you make a mistake in the name in arrayKey then the action here is what will happen which may not be what you expect
            arrayKeyValueNoMatchAction: "add" 
            # indicates what array element in the incomming JSON to use as the incomming source, cannot be less than zero              
            arrayIndex: 1
            # what to do if the index is out of bounds of the incomming JSON array see the optins for arrayKeyValueNoMatchAction
            # Note that unlike location array items using a key / key value if you specify add for an indexed item then you will need to include any 
            # key / key value in the arrayValue json.
            arrayIndexMissingError: "add"
            # the config tree to compare against an existing json or (if add is selected and the existing isn't found) to add.
            # note that if chosing add and a search on the key you will then the key  : keyValue will be added to this automatically, but not if using index
            # Of course this also MUST confirm to the expected input formats as it's passed on to kubernetes, so for example specifying 256 for memory 
            # will actuall mean 256 bytes as that's how kubernrtes interprets it, in reality for that case you'd probabaly want 256Mi meaning 25 Mega bytes
            arrayValue:
              resources:
                requests:
                  cpu: "250m"
                  memory: "4Gi"
                limits:
                  cpu: "500m"
                  memory: "8Gi"
          # is required, can be either key or index (case insensitive) 
          # if key then you need a arrayKey, arrayKeyValue and arrayValue config entry and optionally arrayKeyMissingError and arrayKeyValueNoMatchAction
          # if index then you need an arrayIndex with the value indicating the array oindex to use in the json and optionally arrayIndexMissingError
          # Note that if you are adding items to the array using an index then arrayKey and arrayKeyValue are also required.
          - arrayElementConfigType: "index"
            arrayIndex: 1
            # what to do if the index is out of bounds of the incomming JSON array see the optins for arrayKeyValueNoMatchAction
            # Note that unlike location array items using a key / key value if you specify add for an indexed item then you will need to include any 
            # key / key value in the arrayValue json.
            arrayIndexMissingError: "add"
            # the config tree to compare against an existing json or (if add is selected and the existing isn't found) to add.
            # note that if chosing add and a search on the key you will then the key  : keyValue will be added to this automatically, but not if using index
            # Of course this also MUST confirm to the expected input formats as it's passed on to kubernetes, so for example specifying 256 for memory 
            # will actuall mean 256 bytes as that's how kubernrtes interprets it, in reality for that case you'd probabaly want 256Mi meaning 25 Mega bytes
            arrayValue:
              # the substitution engine should kick in and update the placeholders here
              image: "{{imageRegistry.localOCIR}}/{{imageRegistry.namespace}}ngnix:latest"
          # the end of the containers array processing, this will just force a node selector for the pod
          nodeSelector:
            type: "cpu-large"
  memoryIntensive : 
    spec:
      strategy:
        type: RollingUpdate
        rollingUpdate:
          maxSurge: 1
          maxUnavailable: 0 
      template:
        spec:
          nodeSelector:
            type: "memory-large"
```

The structure is as follows :

 `  mappings:` this section defined the mappings, it is a set of objects which are identified by the mapping name, in the example above that would be `cpuIntensive` and `memoryIntensive`
 
 Within each mapping the structure should match that of the deployment. Any item that exists in the mapping but not in the deployment will be added to the deployment as a JSON object. If the deployment has an object for the mapping then all of the items within that entry will be checked recursively. If both the deployment and the mappings have a leaf object then any value in the mapping will replace that in the deployment.
 
Arrays (for example a containers list) are more complicated as you can't guarantee what the user provides is what reaches you as there may be a previous mutating admission controller that modifies the deployment (for example a service mesh adding a sidecar container) To address this if you want to track against an object that is a list in the deployment then it must also be a list in the mapping definition, however the mapping definition needs instructions on what entry in the deployment list to modify. Thus (as seen in the example above) to the spec / template / spec / containers there is an array of one or more mapping structures. Each of these mapping structures is processed in order and must indicate how to locate the relevant entry in the array in the deployment (or a new item added) as described below. Note that for either approach you can have multiple entries that target the same item in the deployment array, they will be processed in the order defined in the mapping config, but the deployment provided as input is **not** updated, so if for example you added a new container in a mapping section, then tried to modify it you would not be modifying the entry you just added, but may well be adding a second entry (as at that stage the changes have not been applied to the deployment, that's done when the patches are returned to Kubernetes)

Note that if adding an array (say a set of volumes) where the array did not previously exist you can just include them as an array in the mapping, and don't need to use the arrray* keys below, you can just have the contents of the array as the entry itself (so no arrayValue approach needed). It's only if you want to locate and modify specific items in an already existing array that you need to follow the approach below.
 
All config array mappings that will modify specific elements in the deployment list will need to define the approach to locating deployment items. this means that you will need an `arrayElementConfigType` key for the array entry, the code understands values for `key` and `index` anything else will throw an error. 
 
Where you know **for certain** that the deployment array will always be in the order you want then you can just index into the the array. For this mapping input array element set `arrayElementConfigType` to `index`, then set `arrayIndex` to be the index (starting at 0) in the incoming array. The code will locate the array entry in the deployment at that index and the mapping specification held in the `arrayValue` and will be applied. If the array index does not exist (say you entered 2 but the array only had 0 and 1) then the `arrayIndexMissingError` is looked at, if this is `error` then the entire deployment will be rejected, if it's `ignore` then this array element in the mapping will be ignored, if it's `add` then a new array entry will be added to the deployment based on the contents of the `arrayValue` key.

If you do not know for certain the index of the modification array in the deployment array then you can search based on a key. This let's you specify a key name to look for and value that indicates a match, then the processing will continue on the first element that matches. The `arrayKey` item will specify the name of the key to look for and `arrayKeyValue` the value to match against, both `arrayKey` and `arrayKeyValue` must be strings. As an example if searching for a container called `nginx` you might have `arrayKey` set to `name` and `arrayKeyValue` set to `nginx`. The code will then look for the first item in the list with  `name:nginx`. If the key can't be found than if `arrayKeyMissingError` is `true` the deployment will be rejected, if it is `false` then that entry in the deployment will be skipped and the code will check the next one (this allows control in cases where the specified key is not always be present the the object sin the deployment array).

If there is no entry in the array with a matching key / key value then the `arrayKeyValueNoMatchAction` is looked at, if this is `error` then the entire deployment will be rejected, if it's `ignore` then this array element in the mapping will be ignored, if it's `add` then a new array entry will be added to the deployment based on the contents of the `arrayValue` key to avoid duplication if adding an item where there is a missing key : keyValue field then the code will automatically add an entry to the resulting object who's key it the contents of `arrayKey` and value is the string `arrayKeyValue`.

### The substitution configuration

Due to the limitations of the Helidon configuration system currently you can only specify placeholders in Strings (there being no mechanism to identify it a confoiguration value of "1234" should be treated as a string "1234 or an integer 1234

The code will accept substitutions as part of the config tree anywhere you can feed them in. Note that in the case of a placeholder being in the incoming deployment AND a replacement being specified in the mapping then the mapping will take priority and any placeholders in the mapping will be substituted

For example a mapping may have a arrayValue that includes

`image: "{{imageRegistry.localOCIR}}/{{imageRegistry.namespace}}ngnix:latest"`

The substitutions will be applied before the image entry is applied to the container. Note that multiple placeholders can be substituted if desired.

Alternatively the incoming deployment might have an image specification for a container 

`                                "image": "{{imageRegistry.localOCIR}}/ngnix:latest"`

In which case the substitutions will be applied to that (assuming that there is no mapping that overrides this)


For ease of optional file `substitutions/substitutions.yaml` is looked for by the configuration engine and is loaded if present. The reason for having separate files is that it will let you easily use Kubernetes configuration mechanisms (the various things like PVC's, config maps or secrets) to change the substitution options without having to modify the core configuration.

The substitution configuration is as follows

```yaml
# Control of substitutions ins via the mutate.input.substitutions.doSubstitutions property, if this is true
# then substitutions will be looked for, it defaults to false (to preserve previous behavior)
#
# Substitutions are applied to any JSON that is added / replaced through mappings. There 
# can be multiple placeholders in an input string 
#
# If there is no mapping but a substitution is found in the original JSON input then
# it will be substituted (maepings will override anything in the input JSON)
#
# In the input text a substitution placeholder will start with the value in mutate.input.substitutions.substitutionStart
# if it's not provided it will default to {{ (this is used in the examples below)
#
# In the input text a substitution placeholder will end with the value in mutate.input.substitutions.substitutionEnd
# if it's not provided it will default to }} (this is used in the examples below)
#
# If there is no substitution found for a placeholder then an error is generated and the deployment will not proceed.
#
substitutions:
  # This is a set of name / value pairs for potential substitutions, ONLY string values will be accepted
  # if a substitution is needed then the EXACT name is looked for, this is case sensitive.
  # so for example looking at this then if the substitution found a name of {{testValue} then
  # it would be replaced with "1"
  testValue: 1
  # If a matching name is found then the value is applied
  # names can be grouped together for convenience of management HOWEVER
  # in that case the name used in the substitution must include all elements of the name separated .
  # so basically JSON object notation.
  # so for example looking at this then if the substitution found a name of {{imageRegistry.localOCIR}} then
  # it would be replaced with "fra.ocir.io"
  imageRegistry:
    localOCIR: "fra.ocir.io"
 ```
 
 ### Testing the engine
 
 You can run the mutation engine as a "normal" java program provided you have setup the appropriate certificates as desceribed above.
 
 Once running access it using the URL https://localhost:8080
 
 A test deployment is provided which when run against the sample. In the home directory of the project enter
 
 ```bash
 <copy>curl -ivk -X POST --header "Content-Type: application/json" --data @test/sample-data.json https://localhost:8080/mutate</copy>
 ```
 
 
## Disclaimer

ORACLE AND ITS AFFILIATES DO NOT PROVIDE ANY WARRANTY WHATSOEVER, EXPRESS OR IMPLIED, FOR ANY SOFTWARE, MATERIAL OR CONTENT OF ANY KIND

## Copyright

Copyright (c) 2023 Oracle and/or its affiliates. All rights reserved.
The Universal Permissive License (UPL), Version 1.0