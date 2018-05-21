## Running on Kubernetes

This project uses [Lightbend Orchestration for Kubernetes](https://developer.lightbend.com/docs/lightbend-orchestration-kubernetes/latest/) to simplify deployment of Chirper to [Kubernetes](https://kubernetes.io/). Follow the steps below to install Chirper in your own local Kubernetes environment.

### Setup

You'll need to ensure that the following software is installed:

* [Docker](https://www.docker.com/)
* [kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl)
* [Minikube](https://github.com/kubernetes/minikube) v0.25.0 or later (Verify with `minikube version`)
* [Helm](https://github.com/kubernetes/helm)
* [reactive-cli](https://developer.lightbend.com/docs/reactive-platform-tooling/latest/cli-installation.html#install-the-cli) 0.9.0 or later (Verify with `rp version`)

### Choose Deployment Type

Once you've set up your environment, you can deploy Chirper to Kubernetes. The steps differ depending on whether you want to deploy in a development or production environment.

During development, it is simpler to deploy all services with a single sbt task. The *Development Workflow* section describes how to do this. It will build Docker images for each subproject, generate Kubernetes YAML, and deploy them into your Minikube using `kubectl`.

The *Operations Workflow* section describes the steps for deploying in a production environment. You will use sbt to build the images. The [reactive-cli](https://github.com/lightbend/reactive-cli) is then used to generate YAML for deployment to the Kubernetes cluster. Because the production environment is more complex, additional steps are required as described below.

### Build & Deploy

#### 1. Environment Preparation

##### Install reactive-cli

See [Lightbend Orchestration for Kubernetes Documentation](https://developer.lightbend.com/docs/lightbend-orchestration-kubernetes/latest/cli-installation.html#install-the-cli)

Ensure you're using `reactive-cli` 0.9.0 or newer. You can check the version with `rp version`.

##### Start minikube

> If you have an existing Minikube, you can delete your old one start fresh via `minikube delete`

##### Enable Ingress Controller

```bash
minikube addons enable ingress
```

```bash
minikube start --memory 6000
```

##### Setup Docker engine context to point to Minikube

```bash
eval $(minikube docker-env)
```

#### 2a. Development Workflow

> Note that this is an alternative to the Operations workflow documented below.

`sbt-reactive-app` defines a task, `deploy minikube`, that can be used to deploy all aggregated subprojects to your running Minikube. It also installs the [Reactive Sandbox](https://github.com/lightbend/reactive-sandbox/) if your project needs it, e.g. for Lagom applications that use Cassandra or Kafka.

##### Build and Deploy Project

`sbt> deploy minikube`

Once completed, Chirper and its dependencies should be installed in your cluster. Continue with step 3, Verify Deployment.

#### 2b. Operations Workflow

> Note that this is an alternative to the Development workflow documented above.

##### Install Reactive Sandbox

The `reactive-sandbox` includes development-grade (i.e. it will lose your data) installations of Cassandra, Elasticsearch, Kafka, and ZooKeeper. It's packaged as a Helm chart for easy installation into your Kubernetes cluster.

> Note that if you have an external Cassanda cluster, you can skip this step. You'll need to change the `cassandra_svc` variable (defined below) if this is the case.

If your cluster has [RBAC](https://kubernetes.io/docs/admin/authorization/rbac/) enabled (most do), first we'll need to create Kubernetes service account and a role binding for tiller, Helm server:

```bash
kubectl -n kube-system create sa tiller
kubectl create clusterrolebinding tiller --clusterrole cluster-admin --serviceaccount=kube-system:tiller
```

Then we can install Helm to the cluster:

```bash
helm init --service-account tiller
helm repo add lightbend-helm-charts https://lightbend.github.io/helm-charts
helm update
```

Verify that Helm is available (this takes a minute or two):

```bash
kubectl --namespace kube-system get -w deploy/tiller-deploy
```

> The `-w` flag will watch for changes. Use `CTRL-c` to exit.

```
NAME            DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
tiller-deploy   1         1         1            1           3m
```

Install the sandbox. Since Chirper only uses Cassandra, we're disabling the other services but you can leave them enabled by omitting the `set` flag if you wish.

```bash
helm install lightbend-helm-charts/reactive-sandbox \
    --name reactive-sandbox \
    --set elasticsearch.enabled=false,kafka.enabled=false,zookeeper.enabled=false
```

Verify that it is available (this takes a minute or two):

```bash
kubectl get -w deploy/reactive-sandbox
```

```
NAME               DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
reactive-sandbox   1         1         1            1           1m
```

##### Build Project

```bash
sbt clean docker:publishLocal
```

##### View Images

```bash
docker images
```

##### Configure RBAC

> If your cluster has RBAC disabled you can skip this step.

Most Lightbend Orchestration apps do service discovery using [akka-management](https://github.com/akka/akka-management), and for that to work they need permission to list pods running inside the app's namespace. We'll setup a Kubernetes Role and a RoleBinding for that. Put this in a file called `rbac.yml`:

```
kind: Role
apiVersion: rbac.authorization.k8s.io/v1
metadata:
  name: pod-reader
rules:
- apiGroups: [""] # "" indicates the core API group
  resources: ["pods"]
  verbs: ["get", "watch", "list"]
---
kind: RoleBinding
apiVersion: rbac.authorization.k8s.io/v1
metadata:
  name: read-pods
subjects:
- kind: User
  name: system:serviceaccount:default:default
roleRef:
  kind: Role
  name: pod-reader
  apiGroup: rbac.authorization.k8s.io
```

Now create Role and RoleBinding resources:

```
kubectl apply -f rbac.yml
```

##### Deploy Projects

Finally, you're ready to deploy the services. Be sure to adjust the secret variables and cassanda service address as necessary.

```bash
# Be sure to change these secret values

chirp_secret="changeme"
friend_secret="changeme"
activity_stream_secret="changeme"
front_end_secret="changeme"

# Default address for reactive-sandbox, change if using external Cassandra

cassandra_svc="_cql._tcp.reactive-sandbox-cassandra.default.svc.cluster.local"

# Configure the services to allow requests to the minikube IP (Play's Allowed Hosts Filter)

allowed_host="$(minikube ip)"

# deploy chirp-impl

rp generate-kubernetes-resources "chirp-impl:1.0.0-SNAPSHOT" \
  --generate-pod-controllers --generate-services \
  --env JAVA_OPTS="-Dplay.http.secret.key=$chirp_secret -Dplay.filters.hosts.allowed.0=$allowed_host" \
  --external-service "cas_native=$cassandra_svc" \
  --pod-controller-replicas 2 | kubectl apply -f -

# deploy friend-impl

rp generate-kubernetes-resources "friend-impl:1.0.0-SNAPSHOT" \
  --generate-pod-controllers --generate-services \
  --env JAVA_OPTS="-Dplay.http.secret.key=$friend_secret -Dplay.filters.hosts.allowed.0=$allowed_host" \
  --external-service "cas_native=$cassandra_svc" \
  --pod-controller-replicas 2 | kubectl apply -f -

# deploy activity-stream-impl

rp generate-kubernetes-resources "activity-stream-impl:1.0.0-SNAPSHOT" \
  --generate-pod-controllers --generate-services \
  --env JAVA_OPTS="-Dplay.http.secret.key=$activity_stream_secret -Dplay.filters.hosts.allowed.0=$allowed_host" \
  --pod-controller-replicas 2 | kubectl apply -f -

# deploy front-end

rp generate-kubernetes-resources "front-end:1.0.0-SNAPSHOT" \
  --generate-pod-controllers --generate-services \
  --env JAVA_OPTS="-Dplay.http.secret.key=$front_end_secret -Dplay.filters.hosts.allowed.0=$allowed_host" | kubectl apply -f -

# deploy ingress
rp generate-kubernetes-resources \
  --generate-ingress --ingress-name chirper \
  "chirp-impl:1.0.0-SNAPSHOT" \
  "friend-impl:1.0.0-SNAPSHOT" \
  "activity-stream-impl:1.0.0-SNAPSHOT" \
  "front-end:1.0.0-SNAPSHOT" | kubectl apply -f -
```

#### 3. Verify Deployment

Now that you've deployed your services (using either the Developer or Operations workflows), you can use `kubectl` to
inspect the resources, and your favorite web browser to use the application.

> See the resources created for you

```bash
kubectl get all
```

```
NAME                            DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
deploy/activityservice-v1-5-0   1         1         1            1           2h
deploy/chirpservice-v1-5-0      3         3         3            3           2h
deploy/friendservice-v1-5-0     3         3         3            3           2h
deploy/front-end-v1-5-0         1         1         1            1           2h

NAME                                   DESIRED   CURRENT   READY     AGE
rs/activityservice-v1-5-0-659877cd49   1         1         1         2h
rs/chirpservice-v1-5-0-6548865dc5      3         3         3         2h
rs/friendservice-v1-5-0-66f688897b     3         3         3         2h
rs/front-end-v1-5-0-87c5b6b79          1         1         1         2h

NAME                            DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
deploy/activityservice-v1-5-0   1         1         1            1           2h
deploy/chirpservice-v1-5-0      3         3         3            3           2h
deploy/friendservice-v1-5-0     3         3         3            3           2h
deploy/front-end-v1-5-0         1         1         1            1           2h

NAME                                   DESIRED   CURRENT   READY     AGE
rs/activityservice-v1-5-0-659877cd49   1         1         1         2h
rs/chirpservice-v1-5-0-6548865dc5      3         3         3         2h
rs/friendservice-v1-5-0-66f688897b     3         3         3         2h
rs/front-end-v1-5-0-87c5b6b79          1         1         1         2h

NAME                                         READY     STATUS    RESTARTS   AGE
po/activityservice-v1-5-0-659877cd49-59z4z   1/1       Running   0          2h
po/chirpservice-v1-5-0-6548865dc5-2fjn6      1/1       Running   0          2h
po/chirpservice-v1-5-0-6548865dc5-kgbbb      1/1       Running   0          2h
po/chirpservice-v1-5-0-6548865dc5-zcc6l      1/1       Running   0          2h
po/friendservice-v1-5-0-66f688897b-d22ph     1/1       Running   0          2h
po/friendservice-v1-5-0-66f688897b-fvndd     1/1       Running   0          2h
po/friendservice-v1-5-0-66f688897b-j9tzj     1/1       Running   0          2h
po/front-end-v1-5-0-87c5b6b79-mvnh8          1/1       Running   0          2h

NAME                  TYPE        CLUSTER-IP   EXTERNAL-IP   PORT(S)                         AGE
svc/activityservice   ClusterIP   None         <none>        10000/TCP,10001/TCP,10002/TCP   2h
svc/chirpservice      ClusterIP   None         <none>        10000/TCP,10001/TCP,10002/TCP   2h
svc/friendservice     ClusterIP   None         <none>        10000/TCP,10001/TCP,10002/TCP   2h
svc/front-end         ClusterIP   None         <none>        10000/TCP                       2h
```

> Open the URL this command prints in the browser

```bash
echo "http://$(minikube ip)"
```