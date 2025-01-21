# kubernetes-mutating-admission-controller

[![License: UPL](https://img.shields.io/badge/license-UPL-green)](https://img.shields.io/badge/license-UPL-green) [![Quality gate](https://sonarcloud.io/api/project_badges/quality_gate?project=oracle-devrel_kubernetes-mutating-admission-controller)](https://sonarcloud.io/dashboard?id=oracle-devrel_kubernetes-mutating-admission-controller)


## Introduction
This project was created to show how a mutating adminssion controller can be used to modify a deployment using a label in the meta data. Origionally it was just gooing to show how you coudl use the label to add a ndoeSelector to the deployment, but as I got into it I realised I coudl make this pretty general purpose.

## Getting Started
As I use Eclipse and when adding an existing projectr to a git repo Eclipse adds the project as a sub folder, not the root level folder then you'll find the actuall project in the MutatingAdmissionControler folder. All of the code and the main README is there.

### Prerequisites
To edit the code you'll need an editor, Java Developer Kit 17 and maven (I use Eclipse with the Maven plugin). To build the container images you'll need a docker compatible runtime.

## Notes/Issues
As far as I know it seems to work pretty well, and is reasonably general putpose when it comes to adding and replacing elemenbts inthe deployment configuration. It is limited in that there is no real support for defining conditional changes though.

## URLs
The [Kubernetes documentation on admission controllers is here](https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/)

## Contributing
This project is open source.  Please submit your contributions by forking this repository and submitting a pull request!  Oracle appreciates any contributions that are made by the open source community.

## License
Copyright (c) 2024 Oracle and/or its affiliates.

Licensed under the Universal Permissive License (UPL), Version 1.0.

See [LICENSE](LICENSE.txt) for more details.

ORACLE AND ITS AFFILIATES DO NOT PROVIDE ANY WARRANTY WHATSOEVER, EXPRESS OR IMPLIED, FOR ANY SOFTWARE, MATERIAL OR CONTENT OF ANY KIND CONTAINED OR PRODUCED WITHIN THIS REPOSITORY, AND IN PARTICULAR SPECIFICALLY DISCLAIM ANY AND ALL IMPLIED WARRANTIES OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY, AND FITNESS FOR A PARTICULAR PURPOSE.  FURTHERMORE, ORACLE AND ITS AFFILIATES DO NOT REPRESENT THAT ANY CUSTOMARY SECURITY REVIEW HAS BEEN PERFORMED WITH RESPECT TO ANY SOFTWARE, MATERIAL OR CONTENT CONTAINED OR PRODUCED WITHIN THIS REPOSITORY. IN ADDITION, AND WITHOUT LIMITING THE FOREGOING, THIRD PARTIES MAY HAVE POSTED SOFTWARE, MATERIAL OR CONTENT TO THIS REPOSITORY WITHOUT ANY REVIEW. USE AT YOUR OWN RISK. 