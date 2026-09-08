# GraphDB Docker Compose

Docker Compose configs for running [Ontotext GraphDB](https://graphdb.ontotext.com/), used as the Artsdata triplestore.

- `v9/` — legacy config (`culturecreatesorg/graphdb` image)
- `v10/` — current config (`ontotext/graphdb:10.6.3` image)

> For the full step-by-step setup guide (Lightsail instance creation, firewall rules, Nginx, SSL, GraphDB accounts), see the wiki page:
> **[GraphDB on Lightsail: Nginx and SSL](https://github.com/culturecreates/culture-creates-wiki/wiki/GraphDB-on-Lightsail-:-Nginx-and-SSL)**

## Production setup (db.artsdata.ca)

- **Host**: AWS Lightsail instance `production-server-3` (`ca-central-1`), Ubuntu 24.04, tagged `db.artsdata.ca` / `graphdb`
- **Container**: `ontotext/graphdb:10.6.3`, started with `-Xms6g -Xmx8g -XX:+UseG1GC`
- **Deployed compose file**: lives directly on the server at `/home/ubuntu/graphdb/docker-compose.yml` — it is **not** synced from this repo. Its volume mounts differ from `v10/docker-compose.yml` here (server uses separate `data`, `import`, and `logs` mounts under `/home/ubuntu/graphdb/`); treat the files in this repo as reference configs, and diff against the live file before deploying any change.
- **Repository config**: single repository `artsdata`, `query-timeout` = 10s, `query-limit-results` = 10000, ruleset `owl-horst` (see `config.ttl` on the server for the full parameter list)

### Networking / TLS

GraphDB itself only listens on port 7200 (bound directly by Docker, no container-level TLS). In front of it, **Nginx runs on the host** (not in Docker) and terminates TLS:

- Port 80 (HTTP) and port 443 (HTTPS) both proxy to `http://localhost:7200`
- SSL cert via Let's Encrypt/Certbot (`/etc/letsencrypt/live/db.artsdata.ca/`), auto-renewed via the Certbot webroot method (see wiki page for the full renewal-automation writeup)
- Site config: `/etc/nginx/sites-available/db.artsdata.ca.conf`
- An Nginx `map` on the `db.artsdata.ca` vhost appends `&timeout=<n>` to `GET /repositories/artsdata` requests: `timeout=300` when the request's `User-Agent` contains `artsdata.ca` (i.e. our own `ArtsdataGraph`/`GraphClient` services), `timeout=5` otherwise. This sits in front of — and can be shorter than — GraphDB's own 10s repository `query-timeout`, so it's a second place to check when diagnosing query timeouts.

### Logs

| Log | Location | Notes |
|---|---|---|
| GraphDB container | `docker logs graphdb` | Opaque query IDs only, no SPARQL text or client info |
| GraphDB query log | `<graphdb-home>/logs/query.log` | Per-query warnings (e.g. result-limit truncation), no SPARQL text |
| GraphDB slow-query log | `<graphdb-home>/logs/slow-query.log` | Logs full SPARQL text for queries slower than `graphdb.engine.log-slow-queries-time` seconds (JVM property, default 60s — effectively disabled while `query-timeout` is 10s, since queries get killed before qualifying as "slow") |
| Nginx HTTP access log | `/var/log/nginx/access.log` | Port 80 traffic — no per-vhost `access_log` override, falls through to the global default in `nginx.conf` |
| Nginx HTTPS access log | `/var/log/nginx/db.artsdata.ca.access.log` | Port 443 traffic — explicit `access_log` directive on the vhost. Includes client IP and User-Agent per request. |

Both Nginx logs rotate daily (`logrotate`, ~14 days of `.gz` history kept).

### Getting a shell / logs on the instance

There's no static SSH key for this instance — it uses Lightsail's managed default key pair. Get short-lived SSH access via:

```
aws lightsail get-instance-access-details --instance-name production-server-3
```

This returns a temporary SSH certificate + private key (same mechanism as the "Connect" button in the Lightsail console).
