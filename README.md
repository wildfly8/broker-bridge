# broker-bridge

An unofficial, broker-neutral **HTTP + Server-Sent Events API on localhost** for
Interactive Brokers' TWS / IB Gateway. It connects to a logged-in Gateway with
IB's official Java client (TWS API 10.50.02) and exposes quotes, snapshots,
historical bars, contract lookup, orders, order status and fills as plain JSON.

Any program in any language can use it over a socket, without linking IB's
client or knowing its message formats.

- **Licence**: GNU GPL v3 or later (see `LICENSE`). IB's client, included unmodified in
  `third_party/ib-tws-api`, is GPLv3.
- **Runtime**: JDK 25; HTTP requests and IB message dispatch run on virtual threads.
- **Safety**: binds to `127.0.0.1`; order placement is **refused** unless `BRIDGE_ORDERS_ENABLED=true`.
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

| Variable | Default | |
|---|---|---|
| `IB_HOST` / `IB_PORT` | `127.0.0.1` / `4002` | Gateway API socket (4002 paper, 4001 live) |
| `IB_CLIENT_ID` | `11` | API client id |
| `IB_MARKET_DATA_TYPE` | `1` | 1 live, 2 frozen, 3 delayed, 4 delayed-frozen |
| `BRIDGE_BIND` / `BRIDGE_PORT` | `127.0.0.1` / `8090` | API listen address; the API has **no authentication** |
| `BRIDGE_ORDERS_ENABLED` | `false` | only `true` lets orders through |
| `LOG_LEVEL` | `INFO` | |

## API v1

All bodies are JSON; errors are `{"error": "…"}` (plus `"retryable": true` when a retry may succeed).

| Method | Path | Body → response |
|---|---|---|
| GET | `/v1/status` | → `{connected, serverVersion, accounts (masked), lastError, ordersEnabled, since, dataFarms, startedAt}` (`startedAt` changes only when the bridge restarts) |
| POST | `/v1/subscriptions` | `{instrument, tag, snapshot, genericTicks}` → `{subscriptionId}` · 503 disconnected |
| DELETE | `/v1/subscriptions/{id}` | → 204 |
| POST | `/v1/snapshots` | `{instrument, field: BID|ASK|LAST|CLOSE, timeoutMs}` → `{bid, ask, last, close, time}` · 504 timeout |
| POST | `/v1/history` | `{instrument, end, duration, barSize, what, regularHoursOnly, timeoutMs}` → `{bars: [{time, open, high, low, close, volume, count, wap}]}` · 429 pacing |
| POST | `/v1/contracts/resolve` | `{instrument}` → `{instrument, marketName, minTick, priceMagnifier, orderTypes, validExchanges}` · 404 none |
| POST | `/v1/orders` | `{clientOrderId, tag, instrument, side, quantity, type: LIMIT|MARKET|STOP|STOP_LIMIT|LIMIT_IF_TOUCHED, limitPrice, stopPrice (also the touch price), timeInForce: DAY|GTC|IOC, allOrNone, orderRef, modify}` → 202 · 403 disabled · 409 duplicate id · 503 disconnected |
| DELETE | `/v1/orders/{clientOrderId}` | → 202 · 403 disabled · 404 unknown |
| GET | `/v1/events` | `text/event-stream`: `connection`, `quote`, `snapshot-end`, `order-status`, `fill`, `error`; `: heartbeat` every 15 s |

**Instrument**: `{symbol, type: STOCK|OPTION|FUTURE|FUTURE_OPTION|INDEX|FX|COMBO, exchange, primaryExchange, currency,
expiry, right: CALL|PUT, strike, multiplier, brokerId, legs: [{brokerId, ratio, side, exchange}]}`.

**Quote event**: `{tag, subscriptionId, field, code, price, size, option: {impliedVol, delta, gamma, theta, vega,
optionPrice, underlyingPrice}, time}`. `code` is the broker's numeric field code (IB tick type) (0 bid size, 1 bid, 2 ask, 3 ask size,
4 last, 5 last size, 6 high, 7 low, 8 volume, 9 close, 10–13 option computations, 14 open, 45 last timestamp);
delayed-data codes are reported as their live equivalents.

**Tags**: `tag` is any string the client chooses for a subscription or order (e.g. which
of its components should get the data). Every quote, snapshot-end, order-status and fill
event that results from it carries the same `tag`. `orderRef` is passed to the broker as
the order reference.

## Behaviour

- One IB session (client id `IB_CLIENT_ID`). On disconnect (e.g. Gateway's daily restart) it retries every 5 s,
  doubling up to 120 s, then re-sends active streaming subscriptions. Pending snapshot/history/resolve requests
  fail with 503 when the session drops.
- Order ids: the caller's `clientOrderId` is mapped to IB's order id inside the bridge; status, fills and order
  errors come back with the `clientOrderId`. The mapping is in memory: after a bridge restart, earlier orders'
  events are not forwarded.
- Account ids are masked in logs, status and events (`DU*****67`).

## Layout

```
pom.xml                    parent (JDK 25)
third_party/ib-tws-api/    IB's client, unmodified (see its README for source and checksum)
bridge/                    the bridge (io.github.wildfly8.brokerbridge)
Dockerfile                 eclipse-temurin 25 JRE image
```
