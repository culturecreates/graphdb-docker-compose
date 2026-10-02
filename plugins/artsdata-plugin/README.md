# Artsdata plugin for GraphDB

A GraphDB plugin that adds Artsdata's SPARQL functions. It currently provides one function,
`adp:prefLangLiteral`, which returns the value of a property in the preferred language.

## `adp:prefLangLiteral`

```sparql
PREFIX schema: <http://schema.org/>
PREFIX adp: <http://kg.artsdata.ca/plugin#>

SELECT ?item ?name ?description WHERE {
  ?item a schema:Person .
  BIND(adp:prefLangLiteral(?item, schema:name, "en fr") AS ?name)
  BIND(adp:prefLangLiteral(?item, schema:description, "en fr") AS ?description)
}
```

With `docker/sample-data.ttl`:

| item | name | description |
|---|---|---|
| ex:p1 | "John Dupont"@en | "Singer"@en |
| ex:p3 | "Céline Tremblay"@fr | *(unbound)* |
| ex:p4 | "Plain Name" | *(unbound)* |
| ex:p6 | *(unbound)* | *(unbound)* |

The function looks up the values itself, so the query returns one row per item. Don't also add
`?item schema:name ?name` to the query, or you get one row per name.

### Arguments

`adp:prefLangLiteral(?subject, property [, languages])`

| Argument | Description |
|---|---|
| `?subject` | The item. |
| `property` | Any property IRI, e.g. `schema:name`, `schema:description`, `rdfs:label`. |
| `languages` | Optional. Languages in priority order: `"en fr"`, `"en,fr"` or `"en", "fr"`. |

### Which value is returned

1. A value in one of the requested languages, in the order given.
2. A value in the default order, set with `-Dlabel.languages` (default `en,fr`).
   An exact tag (`fr`) beats a regional one (`fr-CA`); tags are matched case-insensitively.
3. An untagged string, or an IRI.
4. A value in any other language.
5. Any other literal (number, date…), then blank nodes.

Ties are broken alphabetically, so the result is the same on every run.

- **IRI values** are returned as plain strings.
- **No value**: the result is unbound and the row is kept. To fall back to the item IRI, use
  `COALESCE(adp:prefLangLiteral(?item, schema:name), STR(?item))`.
- **No row for items without a value**: add `FILTER(BOUND(?name))` after the `BIND`.
- **Wrong arguments** (missing property, or a property given as a string) also give an unbound result.

Besides `BIND`, the function can be used as a `SELECT` expression: `SELECT (adp:prefLangLiteral(...) AS ?x)`.

## How it works

- `PrefLangLiteralFunction` is an RDF4J SPARQL function. For each row it reads the values of
  `(?subject, property, ?)` from the repository and ranks them with `LanguageRanking`.
- `ArtsdataPlugin` is a minimal GraphDB plugin that registers the function when GraphDB starts. This is needed
  because jars under `lib/plugins` are not visible to RDF4J's service loader.
- Nothing is written to the repository.

## GraphDB version

The plugin is compiled against the GraphDB SDK version set in `pom.xml` (`graphdb.version`, currently `10.6.3`).
To use it with another GraphDB version, change that property, rebuild and run the tests.

## Build and test

Requires JDK 11+, Maven and access to `https://maven.ontotext.com`. From this folder:

```bash
mvn clean package
```

This runs the unit tests (`LanguageRankingTest`) and the integration tests against an embedded GraphDB
(`PrefLangLiteralFunctionTest`), and writes `target/artsdata-plugin.jar`.

To try the function in a GraphDB container, either use the repo's v10 local config (see the
[main README](../../README.md#running-locally)) or this folder's standalone config:

```bash
docker compose -f docker/docker-compose.test.yml up -d
```

Then open http://localhost:7200, create a repository, import `docker/sample-data.ttl` and run
`docker/sample-query.rq`.

Deployment to production is described in the [main README](../../README.md#deploying-the-artsdata-plugin).
