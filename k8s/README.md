# Kubernetes (local, kind)

A local Kubernetes cluster with the app and its infrastructure: Postgres with pgvector, Kafka run by the Strimzi
operator (task 3.2), and the app as three Deployments of one image, `api`, `analysis-worker` and `interview-worker`
(task 3.3). Design notes: `docs/LLD.md` §14.2 and §14.3.

```
k8s/
  kind-config.yaml        one-node cluster "repo-interviewer", Kubernetes 1.35.8 (pinned by digest)
  namespace/              the namespace "repo-interviewer", shared by the two below
  strimzi/                the Strimzi 1.3.1 operator (CRDs, RBAC, Deployment), from its pinned release file
  base/                   portable: Postgres (StatefulSet + volume), Kafka 4.2.2 (KRaft, one node), and app/ (ConfigMap,
                          the three Deployments, the Service "api")
  overlays/kind/          kind only: the dev database credentials, Ollama on the host, the local image, smaller JVMs
```

## Before you start

- Docker Desktop, [kind](https://kind.sigs.k8s.io/) 0.33.0 (`brew install kind`), kubectl 1.34 or newer.
- About 8 GB free disk: every image is stored twice (in Docker and inside the kind node). Docker stops answering
  when the disk is full.
- CPU: turn off macOS Low Power Mode (System Settings → Battery). With it on, each app pod took 2 to 3 minutes to
  boot instead of 20 s, probes timed out, and Kubernetes kept restarting pods. Stop other busy containers too (a
  crash-looping one took 2 CPU cores).
- Memory: the infrastructure uses about 1.7 GB of Docker's VM, with the three app pods about 2.8 GB. Stop the Compose
  stack while it runs; its data stays:
  ```bash
  docker compose --profile kafka stop        # later: docker compose --profile kafka start
  ```
- Do not run `./mvnw verify` while the cluster runs: its Testcontainers share Docker's VM, the build takes twice
  as long, and starved pods fail their health probes and get restarted (seen once: the operator 4 times).
  `kind delete cluster --name repo-interviewer` first, or accept that.
- Ollama runs natively on the Mac, not in the cluster (`docs/DECISIONS.md` D-023). Pods reach it at
  `http://host.docker.internal:11434`:
  ```bash
  brew services start ollama && ollama list  # must show nomic-embed-text
  ```

## Create the cluster

```bash
kind create cluster --config k8s/kind-config.yaml

# Load the images from Docker into the node, so the node does not download them again. One platform only:
# with Docker's containerd image store, `kind load docker-image` fails on multi-platform images.
PLATFORM=$(docker version -f '{{.Server.Os}}/{{.Server.Arch}}')
for image in pgvector/pgvector:pg17 quay.io/strimzi/operator:1.3.1 quay.io/strimzi/kafka:1.3.1-kafka-4.2.2; do
  docker pull "$image"
  docker save --platform "$PLATFORM" -o /tmp/kind-image.tar "$image"
  kind load image-archive --name repo-interviewer /tmp/kind-image.tar
done
rm /tmp/kind-image.tar

# The operator first: it adds the Kafka resource types. --server-side because its CRDs are too big for a
# client-side apply; use it for every apply here, so one field manager owns the fields.
kubectl apply --server-side -k k8s/strimzi
kubectl -n repo-interviewer rollout status deployment/strimzi-cluster-operator --timeout=5m

# Then Postgres and Kafka.
kubectl apply --server-side -k k8s/overlays/kind
kubectl -n repo-interviewer wait pod -l app.kubernetes.io/name=postgres --for=condition=Ready --timeout=5m
kubectl -n repo-interviewer wait kafka/events --for=condition=Ready --timeout=10m
```

## Check

```bash
kubectl -n repo-interviewer get pods,pvc
# NAME                                          READY   STATUS
# pod/events-dual-role-0                        1/1     Running     Kafka (KRaft controller + broker)
# pod/postgres-0                                1/1     Running
# pod/strimzi-cluster-operator-...              1/1     Running
# persistentvolumeclaim/data-events-dual-role-0 Bound   2Gi
# persistentvolumeclaim/data-postgres-0         Bound   2Gi

kubectl -n repo-interviewer exec postgres-0 -- psql -U ri -d repo_interviewer -c 'SELECT version()'
kubectl -n repo-interviewer exec events-dual-role-0 -- \
  bin/kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status
kubectl -n repo-interviewer exec events-dual-role-0 -- \
  curl -s -o /dev/null -w '%{http_code}\n' http://host.docker.internal:11434/api/tags   # 200
```

Inside the cluster: Postgres at `postgres:5432` (database `repo_interviewer`), Kafka at
`events-kafka-bootstrap:9092`.

## Deploy the app (task 3.3)

```bash
# The image (Dockerfile, task 3.1), loaded into the node like the others.
docker build -t repo-interviewer:local .
docker save --platform "$(docker version -f '{{.Server.Os}}/{{.Server.Arch}}')" -o /tmp/kind-image.tar repo-interviewer:local
kind load image-archive --name repo-interviewer /tmp/kind-image.tar && rm /tmp/kind-image.tar

# The API key, straight from .env into a Secret: never printed, never in a file in the repo.
kubectl -n repo-interviewer create secret generic anthropic \
  --from-env-file=<(grep '^ANTHROPIC_API_KEY=' .env)

# The ConfigMap, the three Deployments and the Service (the same command as above: it applies everything).
kubectl apply --server-side -k k8s/overlays/kind
for role in api analysis-worker interview-worker; do
  kubectl -n repo-interviewer rollout status deployment/$role --timeout=8m
done
```

On a laptop, three JVMs booting at once can starve each other. If a rollout times out, start them one at a time:
`kubectl -n repo-interviewer scale deployment analysis-worker interview-worker --replicas=0`, wait for `api`, then
scale each worker back to 1.

Use it through a port-forward (the Service has no address outside the cluster):
```bash
kubectl -n repo-interviewer port-forward svc/api 8086:8080
curl localhost:8086/actuator/health/readiness        # {"status":"UP"}
# GraphQL at http://localhost:8086/graphql, GraphiQL at http://localhost:8086/graphiql (stage environment)
```

Each pod's log shows only its own consumer groups: `api` the fan-out `interview-events-<instance id>`,
`analysis-worker` `analysis-ingest`, `-embed` and `-summary`, `interview-worker` `interview-evaluate` and
`-orchestrate`. A new image: build, load, then `kubectl -n repo-interviewer rollout restart deployment/<role>`.

## Stop and start again

```bash
kind delete cluster --name repo-interviewer   # removes the cluster and its data volumes
```

If you ever applied without `--server-side`, the next `kubectl apply --server-side` reports a field conflict with
`kubectl-client-side-apply`; add `--force-conflicts` once to hand the fields over.
