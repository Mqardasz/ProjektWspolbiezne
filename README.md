# Reactive Zabbix Mini Dashboard

A simple server-monitoring dashboard backed by **Spring Boot + Project Reactor**
that polls a Zabbix server via its JSON-RPC API and serves a live HTML dashboard.

## Architecture

```
Zabbix Server
     │  JSON-RPC API
     ▼
Java Backend  (Spring Boot + WebFlux + Project Reactor)
     │  GET /metrics  (JSON)
     ▼
HTML + AJAX Dashboard  →  CPU / RAM / Disk / Network visualisation
```

## Project structure

```
src/
├── main/
│   ├── java/org/example/
│   │   ├── Main.java                        ← Spring Boot entry point
│   │   ├── controller/MetricsController.java ← GET /metrics endpoint
│   │   ├── service/ZabbixService.java        ← Reactor polling + Zabbix API
│   │   └── model/Metrics.java               ← Data model
│   └── resources/
│       ├── application.properties           ← Zabbix connection config
│       └── static/
│           ├── index.html                   ← Dashboard HTML
│           ├── dashboard.js                 ← AJAX polling logic
│           └── style.css                   ← Styles
└── test/java/org/example/
    └── MetricsControllerTest.java
```

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+     |
| Maven | 3.6+   |
| Zabbix Server | 6.x / 7.x (optional – falls back to simulated data) |

## Configuration

Edit `src/main/resources/application.properties`:

```properties
zabbix.api.url=http://<zabbix-host>/zabbix/api_jsonrpc.php
zabbix.api.user=Admin
zabbix.api.password=zabbix
zabbix.host.name=Linux server

server.port=8080
```

> **No Zabbix server?** – The service automatically falls back to randomised
> simulated data when the Zabbix API is unreachable.  The dashboard still works
> and updates every 5 seconds.

## Running the application

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/ZabbixReactor-1.0-SNAPSHOT.jar
```

Or run directly with Maven:

```bash
mvn spring-boot:run
```

Then open **http://localhost:8080** in your browser.

## REST endpoint

| Method | Path      | Description                           |
|--------|-----------|---------------------------------------|
| GET    | /metrics  | Returns the latest metrics snapshot   |

### Example response

```json
{
  "cpu":        34.0,
  "ram":        62.0,
  "disk":       71.0,
  "networkIn":  125.0,
  "networkOut": 98.0,
  "timestamp":  1710000000
}
```

## How it works

1. On startup `ZabbixService` schedules a `Flux.interval(5 s)` pipeline.
2. Each tick authenticates against Zabbix (`user.login`) and fetches item
   values (`item.get`) for CPU, RAM, disk, and network interfaces.
3. Results are mapped to a `Metrics` object and stored in an `AtomicReference`.
4. `MetricsController` exposes the stored snapshot via `GET /metrics`.
5. The browser calls `fetch("/metrics")` every 5 seconds and updates the
   progress bars with the new values.

## Running tests

```bash
mvn test
```
