# Artsdata label function for GraphDB 10.6

A SPARQL function that returns the best-language value of any property.

```sparql
PREFIX schema: <http://schema.org/>
PREFIX adp: <http://kg.artsdata.ca/plugin#>

SELECT ?item ?nameLabel ?descriptionLabel WHERE {
  ?item a schema:Person .
  BIND(adp:label(?item, schema:name, "en fr") AS ?nameLabel)
  BIND(adp:label(?item, schema:description, "en fr") AS ?descriptionLabel)
}
```

| item | nameLabel | descriptionLabel |
|---|---|---|
| ex:person1 | "person1 name in english"@en | "person1 english description"@en |
| ex:person2 | "person2 name in french"@fr | "person2 french description"@fr |

One row per item: don't also add `?item schema:name ?name` to the query, or you get one row per name.

## Arguments

`adp:label(?subject, property [, languages])`

| Argument | |
|---|---|
| `?subject` | the item |
| `property` | any property IRI, e.g. `schema:name`, `schema:description`, `rdfs:label` |
| languages | optional, in priority order: `"en fr"`, `"en,fr"` or `"en", "fr"` |

## Which value is returned

1. The requested languages, in order.
2. The default order, `-Dlabel.languages` (default `en,fr`).
   An exact tag (`fr`) beats a regional one (`fr-CA`); tags are matched case-insensitively.
3. An untagged string, or an IRI.
4. Any other language.
5. Any other literal (number, date…), then blank nodes.

Ties are broken alphabetically, so the result is the same on every run.

- **IRI values** are returned as plain strings.
- **No value**: the result is unbound and the row is kept. To fall back to the item IRI:
  `BIND(COALESCE(adp:label(?item, schema:name), STR(?item)) AS ?nameLabel)`.
- **Wrong arguments** (missing property, property given as a string) also give unbound.

Besides `BIND`, it works as a `SELECT` expression (`SELECT (adp:label(...) AS ?x)`).

## How it works

`LabelFunction` is an RDF4J SPARQL function. For each row it reads the values of `(?subject, property, ?)`
from the repository and ranks them (`LanguageRanking`). `LabelPlugin` is a minimal GraphDB plugin that
registers the function at startup, because jars under `lib/plugins` are not visible to RDF4J's service loader.
Nothing is written to the repository.

## GraphDB version

The plugin is not tied to one GraphDB. It is compiled against the GraphDB SDK version in
`pom.xml` (`graphdb.version`, currently `10.6.3`). To use it with another GraphDB version, change that
property, rebuild and run the tests.

## Build

Requires Maven, JDK 11+ and access to `https://maven.ontotext.com`. From this folder
(`plugins/artsdata-label/`):

```bash
mvn clean package            # unit tests + embedded-GraphDB integration tests
# -> target/artsdata-label.jar
```

## Test locally

```bash
cd v10
docker compose -f docker-compose.local.yml up -d
docker compose -f docker-compose.local.yml logs graphdb | grep -i "artsdata label"
```

```bash
docker compose -f docker/docker-compose.test.yml up -d
```

Then open http://localhost:7200, create a repository, import `docker/sample-data.ttl` and run
`docker/sample-query.rq`.