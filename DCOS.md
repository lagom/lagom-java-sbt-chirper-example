## Running on DC/OS

This project uses [Lightbend Orchestration](https://developer.lightbend.com/docs/lightbend-orchestration/latest/) to
simplify deployment of Chirper to [DC/OS](https://dcos.io/). Follow the steps below to install Chirper in your
own local DC/OS environment.

### Setup

You'll need to ensure that the following software is installed on your Linux or macOS machine:

* [Docker](https://www.docker.com/)
* [dcos](https://github.com/dcos/dcos-cli)
* [reactive-cli](https://developer.lightbend.com/docs/reactive-platform-tooling/latest/cli-installation.html#install-the-cli) 1.1.0 or later (Verify with `rp version`)

Additionally, you'll need access to a DC/OS cluster and a Docker registry.

Finally, make sure that [Cassandra](https://docs.mesosphere.com/services/cassandra/) and [marathon-lb](https://github.com/mesosphere/marathon-lb)
are deployed in your cluster.

#### dcos-vagrant

> [dcos-vagrant](https://github.com/dcos/dcos-vagrant) can be used to provision a DC/OS cluster locally. This method does require a lot of memory to run the various DC/OS and Cassandra nodes, however. Success has been achieved with 32GB machines.

`dcos-vagrant` can be configured to deploy a [built-in Docker registry](https://github.com/dcos/dcos-vagrant/blob/master/examples/private-registry.md) by running the following before issuing `vagrant up`:

```bash
export DCOS_PRIVATE_REGISTRY=true
```

The registry will then be available on `boot.dcos:5000`.

### Build & Deploy

Once you've set up your environment, you can deploy Chirper to DC/OS. This involves building Docker images and then
using a CLI tool to generate the DC/OS Marathon configuration.

#### 1. Environment Preparation

##### Install reactive-cli

See [Lightbend Orchestration for DC/OS Documentation](<todo>)

Ensure you're using `reactive-cli` <fixme> or newer. You can check the version with `rp version`.

##### Verify DC/OS CLI connectivity

```bash
dcos cluster list
```

##### Docker Registry

This workflow relies on building Docker images and pushing them to a registry. Be sure to adjust the command below
to point to your specific registry.

```bash
export DOCKER_REPOSITORY=boot.dcos:5000
```

#### 2. Build Projects

Build and publish the Docker images. Be sure to update the `-DdockerRepository=...` argument below.

```bash
sbt "-DdockerRepository=$DOCKER_REPOSITORY" clean docker:publish
```

#### 3. Deploy Projects

Finally, you're ready to deploy the services. The following commands will generate the marathon configuration for you and pipe it to `dcos marathon app add`.

Be sure to adjust the secret variables and cassanda service address as necessary. Additionally, you'll want to add
an entry to `/etc/hosts` pointing "chirper.dcos" to your public node's IP address. In a production environment, this will need to
be a real DNS entry.

```
# /etc/hosts
192.168.65.60   chirper.dcos
```

**Configuration**

First, set some variables for the following commands. This defines the secrets for the services, the group to deploy them in, the host where they'll be accessed, and the location of Cassandra. Be sure to change as appropriate for your environment.

```bash
chirp_secret=changeme
friend_secret=changeme
activity_stream_secret=changeme
front_end_secret=changeme
dcos_group=chirper
host=chirper.dcos
cassandra_svc="_native-client._node-0-server._tcp.cassandra.mesos"
```

**Deploy chirp-impl**

```bash
rp generate-marathon-configuration "$DOCKER_REPOSITORY/chirp-impl:1.0.0-SNAPSHOT" \
  --namespace "$dcos_group" \
  --env JAVA_OPTS="-Dplay.http.secret.key=$chirp_secret -Dplay.filters.hosts.allowed.0=$host" \
  --marathon-lb-host "$host" \
  --external-service "cas_native=$cassandra_svc" \
  --instances 2 | dcos marathon app add
```

**Deploy friend-impl**

```bash
rp generate-marathon-configuration "$DOCKER_REPOSITORY/friend-impl:1.0.0-SNAPSHOT" \
  --namespace "$dcos_group" \
  --env JAVA_OPTS="-Dplay.http.secret.key=$friend_secret -Dplay.filters.hosts.allowed.0=$host" \
  --marathon-lb-host "$host" \
  --external-service "cas_native=$cassandra_svc" \
  --instances 2 | dcos marathon app add
```

**Deploy activity-stream-impl**

```bash
rp generate-marathon-configuration "$DOCKER_REPOSITORY/activity-stream-impl:1.0.0-SNAPSHOT" \
  --namespace "$dcos_group" \
  --env JAVA_OPTS="-Dplay.http.secret.key=$activity_stream_secret -Dplay.filters.hosts.allowed.0=$host" \
  --marathon-lb-host "$host" \
  --instances 1 | dcos marathon app add
```

**Deploy front-end**

```bash
rp generate-marathon-configuration "$DOCKER_REPOSITORY/front-end:1.0.0-SNAPSHOT" \
  --namespace "$dcos_group" \
  --marathon-lb-host "$host" \
  --env JAVA_OPTS="-Dplay.http.secret.key=$front_end_secret -Dplay.filters.hosts.allowed.0=$host" | dcos marathon app add
```

#### 4. Verify Deployment

Now that you've deployed your services, you can use `dcos` to
inspect the deployment, and your favorite web browser to use the application.


> Open the URL this command prints in the browser

```bash
echo "http://$host/"
```
