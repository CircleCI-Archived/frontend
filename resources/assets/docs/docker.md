<!--

title: Continuous Integration and Delivery with Docker
last_updated: December 8, 2014

-->

CircleCI currently offers beta support for running Docker within build containers.
Docker is an extremely flexible tool that supports many different use cases. This
article attempts to address several of the most popular uses for Docker on CircleCI,
but it is not an exhaustive list. Please [contact us](mailto:sayhi@circleci.com)
if you have questions about uses of Docker that are not covered here.

Note that this article assumes some knowledge of Docker. If you are just getting started
with Docker, then take a look at the [Docker docs](http://docs.docker.com/userguide/)
first.

###Basic usage

To use Docker on CircleCI, simply add Docker as a required service in your
`circle.yml` file like this:

```
machine:
  services:
    - docker
```

You will then be able to use the `docker` command throughout your
`circle.yml` file. Note that you don't need to use `sudo`
to use the command on CircleCI.

###Using a Different Version of Docker

CircleCI uses a custom fork of Docker to work inside of our unprivileged LXC containers.

If you would like to use a version of Docker other than the default version you can download and install a different version from our S3 repository by adding the following to your `circle.yml` file. Be sure to replace the version in `- sudo curl -L -o /usr/bin/docker 'https://s3-external-1.amazonaws.com/circle-downloads/docker-1.8.2-circleci'` with whichever version you would like to use.

```
machine:
  pre:
    - echo 'DOCKER_OPTS="-s btrfs -e lxc -D --userland-proxy=false"' | sudo tee -a /etc/default/docker
    - sudo curl -L -o /usr/bin/docker 'https://s3-external-1.amazonaws.com/circle-downloads/docker-1.8.2-circleci'
    - sudo chmod 0755 /usr/bin/docker
  services:
    - docker
```

###Deployment to a Docker registry

One key use of Docker on CircleCI is to use Docker to build base images to deploy to a
registry like [Docker Hub.](https://hub.docker.com/)
You can do this with the usual`docker build` and `docker push`
commands to build and deploy your image.

Here is an example of a `circle.yml`
file that builds the standard ElasticSearch Docker image and deploys it to
Docker Hub:

```
machine:
  services:
    - docker

dependencies:
  override:
    - docker info
    - docker build -t circleci/elasticsearch .

test:
  override:
    - docker run -d -p 9200:9200 circleci/elasticsearch; sleep 10
    - curl --retry 10 --retry-delay 5 -v http://localhost:9200

deployment:
  hub:
    branch: master
    commands:
      - docker login -e $DOCKER_EMAIL -u $DOCKER_USER -p $DOCKER_PASS
      - docker push circleci/elasticsearch
```

For a complete example of building and deploying a Docker image to a
registry, see the [circleci/docker-elasticsearch](https://github.com/circleci/docker-elasticsearch)
example project on GitHub.

###Application deployment

Another very important use case for Docker containers is "Dockerizing"
applications for deployment purposes. There are countless languages
and technologies that can be deployed this way to a number of hosts that
support Docker containers, but just a couple of examples are provided below
for AWS Elastic Beanstalk and Google Compute Engine with Kubernetes.

####AWS Elastic Beanstalk

The example below demonstrates building and
testing a Dockerized Rails app and deploying the built image to
[AWS Elastic Beanstalk](http://aws.amazon.com/elasticbeanstalk/)
(in fact, you can't even really tell that it's a Rails app because all of the
specifics of the app are handled by the Dockerfile). At a high level, this example
does the following:

1.  Builds a Docker image for the application based on the steps in its Dockerfile
    and tags it with the current git commit SHA
2.  Creates a new container based on that image with the
    `docker run`
    command and starts the app inside it
3.  Runs a (very simple) test against the container
4.  Deploys the freshly built image to Docker Hub
5.  Creates a new AWS Elastic Beanstalk "application version" based on the new image
    by referencing the tag
6.  Updates the Elastic Beanstalk environment to use the new version

```
# circle.yml
machine:
  python:
    version: 2.7.3
  services:
    - docker

dependencies:
  pre:
    - pip install awscli
    - docker build -t circleci/hello:$CIRCLE_SHA1 .

test:
  post:
    - docker run -d -p 3000:3000 -e "SECRET_KEY_BASE=abcd1234" circleci/hello:$CIRCLE_SHA1; sleep 10
    - curl --retry 10 --retry-delay 5 -v http://localhost:3000

deployment:
  elasticbeanstalk:
    branch: master
    commands:
      - docker login -e $DOCKER_EMAIL -u $DOCKER_USER -p $DOCKER_PASS
      - ./deploy.sh $CIRCLE_SHA1
```

```
# deploy.sh
#! /bin/bash

SHA1=$1

# Deploy image to Docker Hub
docker push circleci/hello:$SHA1

# Create new Elastic Beanstalk version
EB_BUCKET=hello-bucket
DOCKERRUN_FILE=$SHA1-Dockerrun.aws.json
sed "s/<TAG>/$SHA1/" < Dockerrun.aws.json.template > $DOCKERRUN_FILE
aws s3 cp $DOCKERRUN_FILE s3://$EB_BUCKET/$DOCKERRUN_FILE
aws elasticbeanstalk create-application-version --application-name hello \
  --version-label $SHA1 --source-bundle S3Bucket=$EB_BUCKET,S3Key=$DOCKERRUN_FILE

# Update Elastic Beanstalk environment to new version
aws elasticbeanstalk update-environment --environment-name hello-env \
    --version-label $SHA1
```

Note that Elastic Beanstalk
also allows you to deploy a Dockerfile and associated source code instead of a built image,
but pre-building the image on CircleCI and running some form of verification on it allows
your deployments to be more deterministic because you remove the build environment as a
variable that differs between test and production.

To see the complete source code for the application from this example,
see [circleci/docker-hello](https://github.com/circleci/docker-hello)
on GitHub.

####Google Compute Engine and Kubernetes

This example shows how to build and deploy a Dockerized application
to [Google Compute Engine](https://cloud.google.com/products/compute-engine/)
using the [Kubernetes](https://github.com/GoogleCloudPlatform/kubernetes)
container cluster manager. This example uses the same Rails application
as the Elastic Beanstalk example above. At a high level, it consists of
the following steps:

1.  Builds a Docker image for the application based on the steps in its Dockerfile
    and tags it with the current git commit SHA
2.  Creates a new container based on that image with the
    `docker run`
    command and starts the app inside it
3.  Runs a (very simple) test against the container
4.  Deploys the freshly built image to a private Docker registry hosted on Google
    Compute Engine (See [google/docker-registry](https://registry.hub.docker.com/u/google/docker-registry/)
    for more information on setting this up)
5.  Updates the Kubernetes replicationController for the Rails app
    to point to the new image based on its SHA
6.  Triggers a rolling update of the pods managed by the Kubernetes controller
    to ensure that the latest image is running on all of them

```
# circle.yml
machine:
  services:
    - docker

dependencies:
  cache_directories:
    - ~/kubernetes
  pre:
    - ./ensure-kubernetes-installed.sh
    - docker build -t $EXTERNAL_REGISTRY_ENDPOINT/hello:$CIRCLE_SHA1 .

test:
  post:
    - docker run -d -p 3000:3000 -e "SECRET_KEY_BASE=abcd1234" $EXTERNAL_REGISTRY_ENDPOINT/hello:$CIRCLE_SHA1; sleep 10
    - curl --retry 10 --retry-delay 5 -v http://localhost:3000

deployment:
  prod:
    branch: master
    commands:
      - docker login -e $DOCKER_EMAIL -u $DOCKER_USER -p $DOCKER_PASS $EXTERNAL_REGISTRY_ENDPOINT
      - envsubst < .kubernetes_auth.template > ~/.kubernetes_auth
      - ./deploy.sh
```

```
# deploy.sh
#!/bin/bash

# Exit on any error
set -e

KUBE_CMD=${KUBERNETES_ROOT:-~/kubernetes}/cluster/kubecfg.sh

# Deploy image to private GCS-backed registry
docker push $EXTERNAL_REGISTRY_ENDPOINT/hello:$CIRCLE_SHA1

# Update Kubernetes replicationController
envsubst < kubernetes/rails-controller.json.template > rails-controller.json
$KUBE_CMD -c rails-controller.json \
  update replicationControllers/railscontroller

# Roll over Kubernetes pods
$KUBE_CMD rollingupdate railscontroller
```

This example assumes that you have already launched a Kubernetes cluster
using something like the `cluster/kube-up.sh` script in the Kubernetes
source. See the README in [GoogleCloudPlatform/kubernetes](https://github.com/GoogleCloudPlatform/kubernetes)
for more detailed instructions on setting up a Kubernetes cluster manager.

It is also assumed in this example that a private Docker registry is hosted
on Google Compute Engine, which is available to Kubernetes minions
[without authentication](https://github.com/GoogleCloudPlatform/kubernetes/issues/499)
by using an internally accessible registry endpoint (i.e.
a port that is protected by the firewall but internally accessible).
The registry can be made available
to CircleCI via a public-facing proxy server protected with SSL and HTTP
basic auth. (Note that [this currently requires trusted SSL certificate](https://github.com/docker/docker/pull/5817)).

To see the complete example project using Google Compute Engine and Kubernetes,
see [circleci/docker-hello-google](https://github.com/circleci/docker-hello-google)
for the project source.

###Running tests in a container

Another use case for Docker on CircleCI is running tests inside of or against
a Docker container. All of the usual Docker commands are available within
your test environment on CircleCI, so if you want to run all of your tests
within a Docker container, then you can simply run the container with the
commands necessary to execute your tests, like this:

```
test:
  override:
    - docker run myimage bundle exec rake test
```

You can also start a container running in detached mode with an exposed
port serving your app as in the other examples above. You can then run
browser tests or other black box tests against the container (in place of
the simple `curl` command in the examples).
Currently, you will not enjoy all the benefits of CircleCI's automatic
test parallelization when running tests within a Docker container. So
if you have a lot of unit tests that take a long time to execute, then
you may want to run them outside of the container and only do certain
integration tests against the built Docker image.
Please don't hesitate to [contact us](mailto:sayhi@circleci.com)
if you have any questions at all about how to best utilize Docker on
CircleCI.

###Docker Exec

If you try to run `docker exec` in our containers, you'll see an error like
the following:

```
Unsupported: Exec is not supported by the lxc driver
```

`docker exec` won't work in our containers because we use the LXC driver
for Docker and `docker exec` for the LXC driver hasn't been implemented.

To work around this, you can the following command, customized for
your container name and the command you want to run, using LXC
directly:

````
sudo lxc-attach -n "$(docker inspect --format '{{.Id}}' $MY_CONTAINER_NAME)" -- bash -c $MY_COMMAND
````

### Caching Docker layers

Docker images aren't cached automatically. At the moment, we have no good
method to cache them although we are trying to find a technical solution.

Docker layers are built on top of copy-on-write filesystems so each layer
only contains the differences from the layer below. This lets them be quite
lightweight and also lets base layers be shared by many different images.
What makes them hard to cache is that the COW linking and structure sharing
is all maintained in kernel-space.

User-space is completely unaware that a filesystem is really a copy-on-write
filesystem. If you were to try to back up docker images you would
end up with each layer as an entirely separate complete copy of the layer
with all the COW structure sharing lost.

If we did take such a copy, then restoring it becomes the big challenge.
We would be missing all the COW information, so we couldn't re-create the sharing
of data across the different filesystems.

We're working to find a solution to the problem. In the meantime, one workaround
is to use docker load/save. Here is a minimal example to get your started:

```
dependencies:
  cache_directories:
    - "~/docker"

  override:
    - if [[ -e ~/docker/image.tar ]]; then docker load -i ~/docker/image.tar; fi
    - docker build -t circleci/elasticsearch .
    - mkdir -p ~/docker; docker save circleci/elasticsearch > ~/docker/image.tar
```

###Some known caching issues

1. The above method can still run into an issue with how Docker uses file modification times
when checking for cached ADD commands, as discussed
[here](https://github.com/docker/docker/issues/7387). One workaround until Docker
adds more options is to
[set file modification times to the commit time](https://git.wiki.kernel.org/index.php/ExampleScripts#Setting_the_timestamps_of_the_files_to_the_commit_timestamp_of_the_commit_which_last_touched_them).

2. Make sure you tag the image you use in the FROM command in your Dockerfile, even if it
is the default "latest" tag (e.g. use `FROM dockerfile/java:latest` instead of just
`FROM dockerfile/java`). Otherwise, Docker will pull down all tags for the image you
specify. This will create significant performance problems because the save/load strategy
described above only caches
the image layers (and thus tags) that you specify in the `docker save` command, so
other tags will be re-pulled on every build if a tag is not specified in the FROM command.

### Connecting to services outside of the container

You can connect to services outside your docker container (like our
pre-installed databases) by using the [docker0 ethernet bridge
device](https://docs.docker.com/articles/networking/). Just make sure
that the outside services are listening for connections on `docker0`—the
simplest way to ensure that is to have the services listen on `0.0.0.0`.

For example, the following configuration will ensure that Postgres is
available to `docker0`:

```
test:
  pre:
    - sudo bash -c "echo \"listen_addresses = '*'\" >>
      /etc/postgresql/9.4/main/postgresql.conf"
    - sudo bash -c "echo \"host all all 0.0.0.0/0 trust\" >>
      /etc/postgresql/9.4/main/pg_hba.conf"
    - sudo /etc/init.d/postgresql restart
```

The similar approach would be required for any other service.
