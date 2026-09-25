# Local development — tazzzo-catalog-service

Two environments, deliberately separate (DOCKER-LOCAL-1):

| | Who starts it | What it is for | Data |
|---|---|---|---|
| **Testcontainers** | `./mvnw test`, automatically | every automated test — the evidence | throwaway, per run |
| **`docker-compose.local.yml`** | you, explicitly | running the application by hand | Mongo persists in a named volume; Redis is ephemeral |

The tests never depend on the compose stack, and the compose stack is never used as test
evidence. Do not mix them, and do not touch containers that belong to other projects.

## 0. Preflight — always

```bash
./scripts/docker-ready.sh
```

It waits up to 60 s for **both** `docker info` and `docker ps` to answer. If it fails, do not
start debugging Java: Docker Desktop is the problem. Recovery is a standing authorisation —
quit Docker Desktop, wait for its processes to exit (terminate only your own Docker Desktop
backend processes if the quit hangs), relaunch, wait until `docker info` **and** `docker ps`
answer, then re-run the affected suite from the beginning. Not without asking the CEO: sudo
repairs, Factory Reset, Reset Kubernetes, deleting the VM disk, `docker system prune -a`,
volume or image deletion, Docker Desktop setting changes.

## 1. Running the tests

```bash
./scripts/docker-ready.sh && ./mvnw test
```

Nothing else to start. Testcontainers pulls `mongo:7` and `redis:7-alpine` the first time.

## 2. Running the application by hand

Start the local stack (MongoDB 7 single-node replica set on 27017 + Redis 7 on host port **6380** —
6379 is already taken by another project's `backend-redis` on this machine):

```bash
docker compose -f docker-compose.local.yml up -d
docker compose -f docker-compose.local.yml ps
```

`mongo-init` runs once, initiates replica set `rs0`, and exits — that is normal. Watch Mongo with
`docker compose -f docker-compose.local.yml logs -f mongo`.

Create your local environment once, then load it into the shell and run:

```bash
cp .env.local.example .env.local          # .env.local is git-ignored; never commit it
# edit .env.local: set TAZZZO_CONSUMER_CURSOR_HMAC_KEY_B64 to "$(openssl rand -base64 32)"
set -a; source .env.local; set +a
./mvnw spring-boot:run
```

### First run against an empty database

On startup the application bootstraps the schema and loads the frozen taxonomy seed (460 nodes,
110 attribute definitions). It does **not** activate a taxonomy release, so every consumer route
answers a fail-closed `503 SERVICE_UNAVAILABLE` until one exists (TR2-CURRENT-1: "current" is an
explicit pointer, never a guess). Activate the baseline once through the CMS API — it needs the CMS
bearer token you set in `.env.local` (`TAZZZO_CMS_TOKEN`):

```bash
curl -s -X POST http://localhost:8080/api/v1/taxonomy/releases \
  -H "Authorization: Bearer $TAZZZO_CMS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"releaseId":"R1","basedOn":null}'
curl -s -X POST http://localhost:8080/api/v1/taxonomy/releases/R1/publish \
  -H "Authorization: Bearer $TAZZZO_CMS_TOKEN"
```

After that `GET /catalog/v1/categories` answers `200` — with an empty `items` list until eligible
products exist, because the consumer plane hides consumer-empty nodes (TR-4A).

Stop the stack when you are done:

```bash
docker compose -f docker-compose.local.yml down     # keeps your Mongo data
```

Never `down -v` unless you mean to erase the local Mongo development data.

## 3. What the values in `.env.local.example` are — and are not

- The limiter numbers (`TAZZZO_RATE_LIMIT_*`) are **permissive local fixtures** so development
  is never throttled. They are **not** production Q5 values; those are load-derived and mandatory
  production configuration (Q5-c).
- `TAZZZO_CONSUMER_CURSOR_HMAC_KEY_B64` is a **local secret**: generate it with
  `openssl rand -base64 32`, keep it in `.env.local`, never commit or reuse it. The production key
  is generated at deployment setup and is not part of this repository (LIST-CURSOR-1).
- `MONGODB_URI` names `replicaSet=rs0` because the write path uses transactions, which MongoDB
  only allows on a replica set.

## 4. Containers you will see

`tazzzo-local-mongo`, `tazzzo-local-mongo-init` (exited), `tazzzo-local-redis` belong to this
stack and use `restart: "no"` — a Docker Desktop restart does not bring them back; you start them.
Containers from other projects on the same machine (for example `backend-redis`) are not ours:
do not delete, rename, re-policy or prune them.
