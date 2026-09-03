# ai-agent-service

Spring Boot backend for an AI assistant web application. It authenticates users with JWT,
manages conversations and chat history, proxies requests to an upstream **opencode** AI server
(synchronously or through an asynchronous job queue), and exposes an admin API for user and
usage management.

The companion frontend lives in `../ai-agent-ui`.

## Features

- **JWT authentication** (stateless) with role-based access (`USER` / `ADMIN`).
- **Chat** against an upstream opencode server with an ordered **model chain**: on an HTTP 429
  rate-limit the service automatically shifts to the next model, with a sticky cooldown.
- **Synchronous** (`POST /api/agent/chat`) and **asynchronous** (`POST /api/agent/chat/async`)
  chat paths. The async path returns a `jobId` and processes inference on a dedicated worker
  pool so HTTP threads are never blocked.
- **Conversation management** — create, read, clear, and delete messages; session history and an
  AI-generated welcome-suggestions endpoint.
- **Crypto session layer** — RSA handshake + per-session AES encryption for client traffic
  (feature-flagged via `app.crypto.enabled`, default true; keypair persisted via `app.crypto.keypair-path`).
- **JWT refresh tokens** — short-lived access tokens + long-lived refresh tokens rotated at `/api/auth/refresh`.
- **Rate limiting** (per-user, sliding window) and unified JSON error responses.
- **Actuator** — `health`, `info`, `metrics`, `ready`, `liveness` endpoints exposed at `/actuator`.
- **Graceful shutdown** — `server.shutdown=graceful` drains in-flight requests (including SSE heartbeats)
  before shutdown completes.
- **Admin API** — user listing (paginated), enable/disable, role changes, deletion (with upstream
  cleanup), and aggregate + per-user usage stats.
- **Open Session In View is disabled**; all lazy-loading is scoped to explicit transactions and
  network I/O runs **outside** transactions.

## Tech Stack

- Java 17, Spring Boot 3.3.5 (Web, Data JPA, Security, Validation)
- H2 (default) / PostgreSQL (production profile)
- JWT (jjwt), BCrypt
- OkHttp (upstream opencode HTTP client)
- Maven (with wrapper `mvnw`)

## Prerequisites

- JDK 17+
- Maven (or use the bundled `mvnw` wrapper)
- A running **opencode** server (default `http://localhost:4096`) for chat functionality

## Configuration

All configuration is in `src/main/resources/application.properties`
(with `application-prod.properties` for the `prod` profile).

### Required environment variables (fail-fast)

The application **refuses to start** without these. There are intentionally no working defaults.

| Variable         | Purpose                                                                                |
|------------------|----------------------------------------------------------------------------------------|
| `JWT_SECRET`     | Signing secret. Must be a Base64 string that decodes to **at least 32 bytes**. Generate with `openssl rand -base64 48` or `node -e "console.log(Buffer.from(require('crypto').randomBytes(48)).toString('base64'))"`. |
| `ADMIN_PASSWORD` | Initial admin password (min 8 chars), used only when **no `ADMIN` account exists yet** to bootstrap the first admin. |

Optional bootstrap variables:

| Variable        | Default                | Purpose                        |
|-----------------|------------------------|--------------------------------|
| `ADMIN_USERNAME`| `admin`                | Username of the bootstrapped admin |
| `ADMIN_EMAIL`   | `<username>@localhost` | Email of the bootstrapped admin |

### Other useful settings

| Variable                              | Default            | Purpose                                              |
|---------------------------------------|--------------------|------------------------------------------------------|
| `AI_OPENCORE_URL` / `ai.opencode.url` | `http://localhost:4096` | Upstream opencode server URL                     |
| `AI_ASYNC` / `ai.opencode.async`      | `true`             | Process AI requests on the async queue              |
| `RATE_LIMIT_PER_MINUTE`               | `60`               | Per-user rate limit (0 disables)                    |
| `H2_CONSOLE_ENABLED`                  | `false`            | Enable the H2 console (local dev only, localhost-bound) |
| `CORS_ALLOWED_ORIGINS` / `app.cors.allowed-origins` | dev origins | Comma-separated allowed browser origins. **Override for production.** |
| `CRYPTO_ENABLED` / `app.crypto.enabled` | `true` | Toggle application-layer AES encryption on/off. Disable for plaintext-over-TLS deployments. |
| `CRYPTO_KEYPAIR_PATH` / `app.crypto.keypair-path` | *(in-memory)* | Persist the RSA keypair to survive restarts. Set to a filesystem path (e.g. `{config}/crypto-keypair`) to avoid invalidating sessions on reboot. |
| `AI_ASYNC-retry` (see below)          | —                  | Async job retry tuning (see below)                  |

### Async job tuning (`application.properties`)

```properties
app.async.core-pool-size=8
app.async.max-pool-size=32
app.async.queue-capacity=5000
app.async.retry-max-attempts=3     # total attempts (incl. first) for HTTP 429, with backoff
app.async.retry-base-delay-ms=2000
app.async.job-retention-hours=24
```

### Model chain

```properties
ai.opencode.models=opencode/big-pickle,opencode/ling-3.0-flash-fin-free,...
ai.opencode.rate-limit-cooldown-seconds=300
```

The first model is tried first; on HTTP 429 the service shifts to the next one and remembers the
shift (sticky) for `rate-limit-cooldown-seconds`. If every model is rate-limited, the 429 is
propagated back to the caller.

## Running

### Local (default profile, H2 in-memory)

PowerShell:

```powershell
$env:JWT_SECRET='<base64-secret>='
$env:ADMIN_PASSWORD='<password>'
.\mvnw.cmd spring-boot:run
```

The app starts Tomcat on `http://localhost:8080`. The H2 console (if enabled) is at
`/h2-console`.

### Production profile (PostgreSQL)

```bash
JWT_SECRET=... ADMIN_PASSWORD=... \
DB_USERNAME=... DB_PASSWORD=... \
CORS_ALLOWED_ORIGINS=https://your-app.example.com \
./mvnw -Pprod spring-boot:run
```

See `application-prod.properties` for the full set of production overrides (datasource, pool
sizing, `ddl-auto=validate`, etc.).

### Building and testing

```bash
./mvnw test        # run the test suite
./mvnw package     # build a runnable jar (tests skipped with -DskipTests)
```

## API

Base path: `http://localhost:8080`. JSON error responses use a unified shape:
`{ "code": "...", "message": "...", "timestamp": <millis> }`.

### Authentication — `/api/auth` (public)

| Method | Path       | Description                                          |
|--------|------------|------------------------------------------------------|
| POST   | `/register`| Register a user (201/200 with `AuthResponse`)        |
| POST   | `/login`   | Log in, returns the JWT (`AuthResponse`)             |
| POST   | `/refresh` | Exchange a refresh token for a new access/refresh pair |
| POST   | `/logout`  | Log out (authenticated)                              |
| GET    | `/me`      | Current user profile (authenticated)                 |

Authenticated requests send `Authorization: Bearer <token>`.

### Agent — `/api/agent` (authenticated)

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/chat`                | Synchronous chat turn → `ChatResponse` (`conversationId`, `reply`, `timestamp`, `model?`) |
| POST   | `/chat/async`          | Submit async turn → `{ jobId, state, message }` (202) |
| GET    | `/jobs/{jobId}/status` | Poll an async job → `ChatJobStatus` |
| GET    | `/jobs/{jobId}/stream` | SSE stream for async job status (exempt from crypto layer) |
| GET    | `/conversation/{id}`   | Message history for a conversation (ownership enforced) |
| POST   | `/conversation`        | Create a new conversation → `{ conversationId }` |
| DELETE | `/conversation/{id}`   | Clear a conversation (200 `{ cleared: true }`) |
| DELETE | `/conversation/{id}/message/{messageId}` | Delete one message (200 `{ deleted: true }`) |
| GET    | `/sessions`            | List the user's conversations (paged, most-recent first) |
| GET    | `/suggestions`         | AI-generated welcome suggestions (string array) |

Missing or non-owned conversations/messages return `404 NOT_FOUND`.

### Admin — `/api/admin` (requires `ADMIN` role)

`Authorization: Bearer <token>` where the token's role is `ADMIN`.

| Method | Path                     | Description                                        |
|--------|--------------------------|----------------------------------------------------|
| GET    | `/users`                 | List users; plain (bounded) or paginated with `?page=&size=` (size clamped to 200) |
| PUT    | `/user/{id}/toggle-access` | Enable / disable a user                          |
| PUT    | `/user/{id}/role`        | Change role (`{"role":"ADMIN"}` or `{"role":"USER"}`) |
| DELETE | `/user/{id}`             | Delete a user + all data + upstream sessions      |
| GET    | `/stats`                 | Aggregate usage stats                              |
| GET    | `/stats/user/{id}`       | Per-user usage stats                               |

### Crypto — `/api/crypto` (public)

| Method | Path        | Description |
|--------|-------------|-------------|
| GET    | `/public-key`| RSA public key (PEM) used to encrypt the client AES key |
| POST   | `/handshake` | Exchange → `{ sessionId }` for per-session AES encryption |

## Behavior & design notes

- **Transactions:** network calls to opencode run outside any DB transaction; persistence is
  scoped via `TransactionTemplate`. `deleteUser`/`clearConversation` delete DB rows in one
  transaction after best-effort upstream cleanup.
- **N+1 avoidance:** user/stats endpoints use bulk/batched queries (`sumTokensPerDaySince`,
  `countByConversationIdAndRole`, `bulkDeleteByConversationIds`, etc.).
- **Model reply** includes the selected model (`ChatResponse.model`) when a model chain applies.
- **Consistency:** feedback — a failed request does not roll back already-persisted conversations;
  upstream failures are logged and surfaced as `UPSTREAM_ERROR` without leaking upstream bodies.
