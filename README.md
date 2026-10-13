# broker-bridge

A **broker-neutral HTTP + Server-Sent Events API on localhost** for trading programs.
It exposes quotes, snapshots, historical bars, contract lookup, orders, order status
and fills as plain JSON, in broker-neutral terms: `instrument`, `side`, `LIMIT`,
`fillId`, `brokerId`.

Any program in any language can use it over a socket. A client never links a broker's
client library, never compiles against one and never learns its message formats — that
is the point of the bridge, and it is what keeps a broker's licence terms off the
calling program.

## Broker support

| Broker | Status | How |
|---|---|---|
| **Interactive Brokers** | implemented, the only one today | IB's official Java client (TWS API 10.50.02) against a logged-in TWS or IB Gateway |
| anything else | not implemented | API v1 reserves an optional `venue` field, which defaults to IB (see Compatibility) |

The API is broker-neutral; this program is not broker-agnostic. Six classes compile
against IB's client (`BrokerService`, `Orders`, `Contracts`, `IbCallbacks`, `IbClient`,
`SocketIbClient`), so IB types reach into the service layer, not just an edge adapter.
Adding a second broker means a second implementation under the same v1 wire API, not a
configuration switch. Nothing IB-specific crosses the API: no IB class name, enum or
message layout appears in a request, a response or an event.

- **Licence**: GNU GPL v3 or later (see `LICENSE`). IB's client, included unmodified in
  `third_party/ib-tws-api`, is GPLv3, which is why it lives in this separate program
  rather than inside any caller.
- **Runtime**: JDK 25; HTTP requests and broker message dispatch run on virtual threads.
- **Safety**: binds to `127.0.0.1`; a new order and a modification are **refused** unless `BRIDGE_ORDERS_ENABLED=true` (a cancel only reduces risk and is taken either way); and when
  `BRIDGE_ORDER_TOKEN_FILE` (or `BRIDGE_ORDER_TOKEN`) is set, `POST`/`DELETE /v1/orders` need that token as a
  bearer token, so a caller that shares the network namespace but not the secret cannot place or cancel an order.
- Not affiliated with or endorsed by Interactive Brokers. "Interactive Brokers", "IBKR",
  "TWS" and "IB Gateway" are trademarks of their owners, used here only to say what this
  program connects to.

## Build and run

```bash
mvn package                                  # JDK 25 + Maven 3.9
IB_HOST=127.0.0.1 IB_PORT=4002 java -jar bridge/target/broker-bridge.jar
curl -s localhost:8090/v1/status

docker build -t broker-bridge .              # or as a container
docker run --rm --network host broker-bridge
```

GitHub Actions (`.github/workflows/ci.yml`) builds and tests on every push and pull request (`mvn -B verify`: all the unit tests under `bridge/src/test`, whatever their number) and
then builds the image, starts it without a Gateway and checks that `/v1/status` answers with orders refused.

Bridge settings, independent of the broker:

| Variable | Default | |
|---|---|---|
| `BRIDGE_BIND` / `BRIDGE_PORT` | `127.0.0.1` / `8090` | API listen address; the API has **no authentication** except the order routes below |
| `BRIDGE_ORDERS_ENABLED` | `false` | only `true` (any case) lets new orders and modifications through; `1` and `yes` do not. A cancel does not need it |
| `BRIDGE_ORDER_TOKEN_FILE` / `BRIDGE_ORDER_TOKEN` | unset | the bearer token the order routes (`/v1/orders`) require (`Authorization: Bearer <token>`, otherwise 401 `WWW-Authenticate: Bearer` before anything reaches the broker); the file wins; at least 16 characters, an unusable value stops the start and is never quoted (a file the container's user `bridge` cannot read is unusable: give the container the file's group, `group_add` in compose); unset: the order routes are open like the others (and a start with orders enabled says so as a warning) |
| `BRIDGE_DATA_DIR` | unset (image: `/data`) | journal of order events and order ids, so replay survives a restart |
| `BRIDGE_REPLAY_MAX` | `100000` | order events kept for replay |
| `BRIDGE_WATCHDOG` | `on` | `off` stops the bridge from ending itself when its connection machinery is stuck (see Behaviour) |
| `LOG_LEVEL` | `info` | `trace`, `debug`, `info`, `warn` or `error` (logging is `slf4j-simple`) |

Settings of the IB adapter. They keep their `IB_` names because they configure IB's own
client and nothing else reads them:

| Variable | Default | |
|---|---|---|
| `IB_HOST` / `IB_PORT` | `127.0.0.1` / `4002` | Gateway API socket (4002 paper, 4001 live) |
| `IB_CLIENT_ID` | `11` | API client id |
| `IB_MARKET_DATA_TYPE` | `1` | 1 live, 2 frozen, 3 delayed, 4 delayed-frozen |

## API v1

All bodies are JSON; errors are `{"error": "…"}` (plus `"retryable": true` when a retry may succeed).

| Method | Path | Body → response |
|---|---|---|
| GET | `/v1/status` | → `{connected, serverVersion, accounts (masked), lastError, ordersEnabled, since, dataFarms, startedAt, durable, linkUp}` (`startedAt` changes only when the bridge restarts; `durable`: the order events are journaled on disk, so a restart keeps them; `linkUp`: false from IB's error 1100 until 1101 or 1102 or a new connection, because IB's own link to the broker's servers can be lost while the socket to the Gateway stays open; orders, modifications and cancels get a retryable 503 meanwhile) |
| POST | `/v1/subscriptions` | `{instrument, tag, snapshot, genericTicks, owner}` → `{subscriptionId}` · 503 disconnected (`owner` is optional: an opaque mark of whoever opened the stream, listed back by the route below) |
| GET | `/v1/subscriptions` | → `{subscriptions: [{subscriptionId, tag, owner, instrument, snapshot, genericTicks}], eventClients}`: the streams the bridge holds, by id (`owner` and `genericTicks` left out when there are none), and the number of clients attached to `/v1/events` now, the caller's own included; needs neither the broker nor a token |
| DELETE | `/v1/subscriptions/{id}` | → 204 |
| POST | `/v1/snapshots` | `{instrument, field: BID|ASK|LAST|CLOSE, timeoutMs}` → `{bid, ask, last, close, time}` · 504 timeout |
| POST | `/v1/history` | `{instrument, end, duration, barSize, what, regularHoursOnly, timeoutMs}` → `{bars: [{time, open, high, low, close, volume, count, wap}]}` · 429 pacing, with `Retry-After` when the broker named a wait |
| POST | `/v1/contracts/resolve` | `{instrument}` → `{instrument, marketName, minTick, priceMagnifier, orderTypes, validExchanges}` · 404 none |
| POST | `/v1/orders` | `{clientOrderId, tag, instrument, side, quantity, type: LIMIT|MARKET|STOP|STOP_LIMIT|LIMIT_IF_TOUCHED, limitPrice, stopPrice (also the touch price), timeInForce: DAY|GTC|IOC, allOrNone, orderRef, modify}` → 202 (handed to IB's client: not an acknowledgement, the order's `order-status` events are) · 401 token missing or wrong (when one is configured) · 403 disabled · 409 duplicate id · 503 disconnected, or IB's link to its servers is down (`linkUp`) |
| DELETE | `/v1/orders/{clientOrderId}` | → 202 (also with `BRIDGE_ORDERS_ENABLED=false`: a cancel is not an order) · 401 token missing or wrong (when one is configured) · 404 unknown · 503 disconnected, or IB's link to its servers is down |
| GET | `/v1/events` | `text/event-stream`: `connection`, `quote`, `snapshot-end`, `order-status`, `fill`, `error`; `: heartbeat` every 15 s |

**Instrument**: `{symbol, type: STOCK|OPTION|FUTURE|FUTURE_OPTION|INDEX|FX|COMBO, exchange, primaryExchange, currency,
expiry, right: CALL|PUT, strike, multiplier, brokerId, legs: [{brokerId, ratio, side, exchange}]}`.

**Quote event**: `{tag, subscriptionId, field, code, price, size, option: {impliedVol, delta, gamma, theta, vega,
optionPrice, underlyingPrice}, time}`. `code` is the broker's own numeric field code (with the IB adapter, an IB tick
type) (0 bid size, 1 bid, 2 ask, 3 ask size,
4 last, 5 last size, 6 high, 7 low, 8 volume, 9 close, 10–13 option computations, 14 open, 45 last timestamp);
delayed-data codes are reported as their live equivalents.

**Event ids and resume**: every event has an SSE `id:` (a number that keeps increasing, also
across restarts). Order events (`order-status`, `fill`, and `error` with a `clientOrderId`)
are kept and, with `BRIDGE_DATA_DIR`, journaled to disk (fsync per event). Reconnect with the
standard `Last-Event-ID: <id>` header (or `/v1/events?lastEventId=<id>`) to get every kept order
event after that id, in order, followed by live events, with no gap or repeat in between. Quotes
are not replayed. If the id is older than what was kept you first get
`event: replay-truncated` `{after, oldestKept}` (`oldestKept` is the id of the first event the bridge still keeps, or the id
its next event will get when it keeps none, so the newest event it no longer has is `oldestKept - 1`): also when the journal is
new or was lost with its volume (the bridge cannot know what happened before it), and for the first start on an empty volume. A restart that finds its
journal keeps the horizon the journal recorded and invents none. After those replayed order events (and when there is
nothing to replay) the bridge sends one `event: replay-complete` with id 0, `{replayed, after}` (`after` only when the
client asked to resume). It is not journaled. On every broker connection the bridge also asks
the broker for its open and completed orders and for the day's executions (see Behaviour), so fills made while it was
down are sent too. Fills are unique by `fillId`; delivery is at-least-once, so clients should ignore a `fillId` they
already have.

**Tags**: `tag` is any string the client chooses for a subscription or order (e.g. which
of its components should get the data). Every quote, snapshot-end, order-status and fill
event that results from it carries the same `tag`. `orderRef` is passed to the broker as
the order reference, followed by the `clientOrderId` and the `tag`: the broker gets
`<orderRef>#<clientOrderId>#<tag>` (for example `INV#1791348607#3`; `#` and `%` in the tag are written `%23` and
`%25`; the whole reference may have at most 128 characters, a longer one is refused with 400 before anything is
claimed or sent). The broker returns the reference whenever it reports an order, so the bridge recognises its own
orders from the broker's data alone (see Behaviour: what the broker holds, read at every connect).

**Compatibility**: API v1 only grows by adding optional fields and new event types. Clients
must ignore fields and event types they don't know; the bridge ignores unknown request fields.
A future optional `venue` field (which broker or exchange account an instrument, order or
event belongs to) will default to the only configured venue, IB, when absent, so v1 clients
keep working. Removing or renaming a field, or changing its meaning, needs `/v2`.

## Behaviour

- One broker session (with the IB adapter: client id `IB_CLIENT_ID`). On disconnect (e.g. the Gateway's daily restart)
  it retries every 5 s, doubling up to 120 s, then re-sends active streaming subscriptions. A handshake the Gateway
  accepts and then drops (error 502 while it is restarting) waits that same backoff and does not connect again
  immediately. The handshake itself is bounded (10 s to connect, 15 s for the Gateway's answer): a Gateway that
  accepts the socket and says nothing is given up on. Pending snapshot/history/resolve requests fail with 503 when
  the session drops.
- **Watchdog.** IB's client holds its own lock while it reports a closed socket, so the connection loop never takes a
  lock of its own while it asks the client (a loop that did deadlocked once, on 2026-10-07, and stayed down for three
  days with the Gateway logged in; `IbConnectionLockTest` reproduces it). As a last defence a watchdog thread checks
  every 15 s that the client answers within 45 s and that the connection loop has gone round within 5 minutes; if not,
  it logs a thread dump (locks included) and ends the process (`Runtime.halt`, exit 70), so that the container's
  restart policy starts a new bridge in seconds. `BRIDGE_WATCHDOG=off` turns it off.
- **What the broker holds, read at every connect.** The broker documents that an order status is not guaranteed for every
  change, so the bridge does not rely on being told. After the market-data type and the order ids it asks for the
  client's open orders and its completed orders, then for the day's executions, and it marks the session up
  (`connected: true`, orders are taken, the `connection` event goes out) only when both lists have ended: it waits at most
  15 s for the open orders and 3 s more for the completed ones, and not at all once the connection is lost again. An
  order that the broker lists and the bridge has no record of (its journal was lost) is entered again from the reference
  it carries, with its client id, tag and instrument, and so is the order of an execution; a reference of another shape
  (an order of an older bridge, one typed into the broker's own application) is left alone. An order that the bridge had
  not seen end (its latest kept status is not final, or it has none and the bridge recorded when it was placed) and that
  the broker lists neither open nor completed gets a final `order-status` before `connected` is announced: the status the
  broker gave it among its completed orders (`Filled` or `Cancelled`, with `filled` and `remaining`, `whyHeld` saying that
  the broker listed it as completed), otherwise `Inactive` with `whyHeld` "not among IB's open orders when the bridge
  connected". If the broker does not end its list of open orders in time the bridge goes on, concludes nothing and says so
  in its log. Limits: the broker reports executions since midnight only, so a fill made before midnight while the bridge was
  away is not recovered (the order's final status is, and its `filled` shows what it came to); orders placed under another
  `IB_CLIENT_ID` are not listed for this client and would be concluded `Inactive`: cancel them in the broker's own
  application before changing the client id.
- **Streams a client left behind.** The bridge keeps every streaming subscription until it is told to cancel it, and subscribes
  it again after each reconnect of the broker, so the streams of a client that was stopped hard go on sending quotes, and each
  holds one of the account's market-data lines (IB's default is 100). A client that marks its streams with an `owner` (a run id
  of its own) can list them (`GET /v1/subscriptions`) and cancel the ones that are not its own with `DELETE`; `eventClients` tells
  whether another client is attached to the event stream, which may be a run that is still going and whose streams would be taken
  away.
- Order ids: the caller's `clientOrderId` is mapped to the broker's order id inside the bridge; status, fills and order
  errors come back with the `clientOrderId`. With `BRIDGE_DATA_DIR` the mapping is journaled, so events for
  earlier orders still arrive after a bridge restart. A broker order id that belongs to another order is never used
  again: the counter is kept above the highest id the journal has (the Gateway's "Reset API order ID sequence" or a
  lost settings volume can make the broker's own counter come back lower), and an id taken anyway is skipped (up to
  10,000, then 503). The record of an order whose last status is not final (a GTC order can work for weeks) is not
  dropped for age; the oldest records of ended orders go first, and at most 100,000 are kept in all.
- Account ids are masked in logs, status and events (`DU*****67`).

## Layout

```
pom.xml                    parent (JDK 25)
third_party/ib-tws-api/    IB's client, unmodified (see its README for source and checksum)
bridge/                    the bridge (io.github.wildfly8.brokerbridge)
                           no IB import:  BridgeHttpServer, Model, Json, EventHub, EventLog, BridgeState,
                                          BridgeConfig, BridgeMain, ApiException, IbConnection (reconnect loop),
                                          QuoteFields (the numeric codes are IB tick types)
                           uses IB's client: BrokerService, Orders, Contracts, IbCallbacks, IbClient, SocketIbClient
Dockerfile                 eclipse-temurin 25 JRE image
```
