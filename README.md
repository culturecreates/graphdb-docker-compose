# GraphDB Docker Compose

Docker Compose configs for running [Ontotext GraphDB](https://graphdb.ontotext.com/), used as the Artsdata triplestore.

- `v9/` — legacy config (`culturecreatesorg/graphdb` image)
- `v10/` — current config (`ontotext/graphdb:10.6.3` image)
- `plugins/artsdata-plugin/` — GraphDB plugin with Artsdata's SPARQL functions (currently `adp:prefLangLiteral`,
  the value of a property in the preferred language). Mounted by both v10 configs. See its
  [README](plugins/artsdata-plugin/README.md).

> For the full step-by-step setup guide (Lightsail instance creation, firewall rules, Nginx, SSL, GraphDB accounts), see the wiki page:
> **[GraphDB on Lightsail: Nginx and SSL](https://github.com/culturecreates/culture-creates-wiki/wiki/GraphDB-on-Lightsail-:-Nginx-and-SSL)**

## Running locally

Requires Docker with Compose, plus JDK 11+ and Maven to build the plugin. Run the commands from the repo root.

1. **Build the plugin.** Both v10 configs mount the plugin jar, so build it before starting GraphDB.
   This also runs the plugin's tests:
   ```bash
   mvn -f plugins/artsdata-plugin/pom.xml clean package
   ```
   The jar is written to `plugins/artsdata-plugin/target/artsdata-plugin.jar`. Add `-DskipTests` to skip the tests.

2. **Set your import folder.** `v10/docker-compose.local.yml` mounts `/Users/saumier/data` as the GraphDB import
   directory. Change it to a folder on your machine, or remove the line.

3. **Start GraphDB:**
   ```bash
   docker compose -f v10/docker-compose.local.yml up -d
   ```
   The Workbench is at http://localhost:7200.

4. **Check that the plugin loaded:**
   ```bash
   docker compose -f v10/docker-compose.local.yml logs graphdb | grep -i "artsdata plugin initialized"
   ```

5. **Stop GraphDB:**
   ```bash
   docker compose -f v10/docker-compose.local.yml down
   ```

After changing the plugin, rebuild it (step 1) and restart GraphDB so it loads the new jar:
`docker compose -f v10/docker-compose.local.yml restart graphdb`.

## Production setup (db.artsdata.ca)

- **Host**: AWS Lightsail instance `production-server-3` (`ca-central-1`), Ubuntu 24.04, tagged `db.artsdata.ca` / `graphdb`
- **Container**: `ontotext/graphdb:10.6.3`, started with `-Xms6g -Xmx8g -XX:+UseG1GC`
- **Deployed compose file**: lives directly on the server at `/home/ubuntu/graphdb/docker-compose.yml` — it is **not** synced from this repo. Its volume mounts differ from `v10/docker-compose.yml` here (server uses separate `data`, `import`, and `logs` mounts under `/home/ubuntu/graphdb/`); treat the files in this repo as reference configs, and diff against the live file before deploying any change.
- **Repository config**: single repository `artsdata`, `query-timeout` = 10s, `query-limit-results` = 10000, ruleset `owl-horst` (see `config.ttl` on the server for the full parameter list)

### Deploying the Artsdata plugin

`v10/docker-compose.yml` mounts `/home/ubuntu/graphdb/plugins/artsdata-plugin` into GraphDB's plugin folder and
sets the default language order with `-Dlabel.languages=en,fr`. The live compose file is not synced from this
repo, so:

1. Build the jar: `mvn -f plugins/artsdata-plugin/pom.xml clean package`.
2. Copy `plugins/artsdata-plugin/target/artsdata-plugin.jar` to `/home/ubuntu/graphdb/plugins/artsdata-plugin/`
   on the server.
3. Add `-Dlabel.languages=en,fr` option to `/home/ubuntu/graphdb/docker-compose.yml`.
4. Recreate the container and check the log:
   ```bash
   docker compose up -d --force-recreate graphdb
   docker logs graphdb 2>&1 | grep -i "artsdata plugin initialized"
   ```

**Rollback:** remove the volume line and recreate the container.

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
