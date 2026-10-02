package ca.artsdata.graphdb.plugin;

import com.ontotext.graphdb.Config;
import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.test.functional.base.SingleRepositoryFunctionalTest;
import com.ontotext.test.utils.StandardUtils;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for adp:prefLangLiteral against an embedded GraphDB repository.
 * The default language order is "en,fr" (surefire systemPropertyVariables in the pom).
 */
public class PrefLangLiteralFunctionTest extends SingleRepositoryFunctionalTest {

    private static final String PREFIXES = ""
            + "PREFIX schema: <http://schema.org/>\n"
            + "PREFIX adp: <http://kg.artsdata.ca/plugin#>\n"
            + "PREFIX ex: <http://example.com/>\n";

    @ClassRule
    public static TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

    @Override
    protected RepositoryConfig createRepositoryConfiguration() {
        return StandardUtils.createOwlimSe("rdfsplus-optimized");
    }

    @BeforeClass
    public static void setWorkDir() {
        System.setProperty("graphdb.home.work", String.valueOf(tmpFolder.getRoot()));
        Config.reset();
    }

    @AfterClass
    public static void resetWorkDir() {
        System.clearProperty("graphdb.home.work");
        Config.reset();
    }

    @Before
    public void loadData() throws IOException {
        try (RepositoryConnection connection = getRepository().getConnection()) {
            connection.clear();
            connection.add(getClass().getResource("/test-data.ttl"), RDFFormat.TURTLE);
        }
    }

    // ---- helpers ----

    private List<BindingSet> rows(String body) {
        List<BindingSet> result = new ArrayList<>();
        try (RepositoryConnection connection = getRepository().getConnection();
             TupleQueryResult rows = connection.prepareTupleQuery(PREFIXES + body).evaluate()) {
            rows.forEach(result::add);
        }
        return result;
    }

    /** item local name -> row; fails if an item appears in more than one row. */
    private static Map<String, BindingSet> byItem(List<BindingSet> rows) {
        Map<String, BindingSet> result = new HashMap<>();
        for (BindingSet row : rows) {
            String item = row.getValue("item").stringValue().replace("http://example.com/", "");
            assertFalse("more than one row for " + item, result.containsKey(item));
            result.put(item, row);
        }
        return result;
    }

    /** Label of one property for every person, keyed by local name. */
    private Map<String, BindingSet> labels(String property, String languageArgs) {
        return byItem(rows("SELECT ?item ?label WHERE {\n"
                + "  ?item a schema:Person .\n"
                + "  BIND(adp:prefLangLiteral(?item, " + property + languageArgs + ") AS ?label)\n"
                + "}"));
    }

    private static void assertLabel(Map<String, BindingSet> rows, String item, String var, String text, String lang) {
        BindingSet row = rows.get(item);
        assertTrue(item + " missing", row != null);
        Value v = row.getValue(var);
        assertTrue(item + " has no " + var, v instanceof Literal);
        Literal l = (Literal) v;
        assertEquals(item, text, l.getLabel());
        String actual = l.getLanguage().orElse(null);
        assertEquals(item + " language", lang == null ? null : lang.toLowerCase(),
                actual == null ? null : actual.toLowerCase());
    }

    // ---- the target query ----

    @Test
    public void targetQuery() {
        Map<String, BindingSet> rows = byItem(rows(""
                + "SELECT ?item ?nameLabel ?descriptionLabel WHERE {\n"
                + "  ?item a schema:Person .\n"
                + "  BIND(adp:prefLangLiteral(?item, schema:name, \"en fr\") AS ?nameLabel)\n"
                + "  BIND(adp:prefLangLiteral(?item, schema:description, \"en fr\") AS ?descriptionLabel)\n"
                + "}"));
        assertEquals("one row per person", 7, rows.size());
        assertLabel(rows, "person1", "nameLabel", "person1 name in english", "en");
        assertLabel(rows, "person1", "descriptionLabel", "person1 english description", "en");
        assertLabel(rows, "person2", "nameLabel", "person2 name in french", "fr");
        assertLabel(rows, "person2", "descriptionLabel", "person2 french description", "fr");
    }

    // ---- languages ----

    @Test
    public void requestedOrderIsRespected() {
        assertLabel(labels("schema:name", ", \"fr en\""), "person1", "label", "person1 name in french", "fr");
    }

    @Test
    public void languagesAsSeparateArguments() {
        assertLabel(labels("schema:name", ", \"fr\", \"en\""), "person1", "label", "person1 name in french", "fr");
    }

    @Test
    public void defaultOrderWhenNoLanguagesGiven() {
        assertLabel(labels("schema:name", ""), "person1", "label", "person1 name in english", "en");
    }

    @Test
    public void fallbackOrder() {
        Map<String, BindingSet> rows = labels("schema:name", ", \"de\""); // nobody has "de" -> defaults en,fr
        assertLabel(rows, "person1", "label", "person1 name in english", "en");
        assertLabel(rows, "person3", "label", "Céline Tremblay", "fr-CA"); // regional tag
        assertLabel(rows, "person4", "label", "Plain Name", null);          // untagged beats "es"
        assertLabel(rows, "person6", "label", "Only name", "it");           // any other language
        assertLabel(rows, "person7", "label", "Anne", "en");                // alphabetical tie-break
    }

    // ---- IRIs and missing values ----

    @Test
    public void iriValueIsReturnedAsString() {
        Map<String, BindingSet> rows = labels("schema:sameAs", "");
        assertLabel(rows, "person5", "label", "http://example.com/other/p5", null);
        assertEquals(XSD.STRING, ((Literal) rows.get("person5").getValue("label")).getDatatype());
    }

    @Test
    public void requestedLanguageBeatsIri() {
        assertLabel(labels("schema:genre", ", \"en\""), "person5", "label", "Jazz", "en");
    }

    @Test
    public void missingValueIsUnbound() {
        Map<String, BindingSet> rows = labels("schema:description", ", \"en\"");
        assertEquals("rows are kept", 7, rows.size());
        assertNull(rows.get("person6").getValue("label"));
    }

    @Test
    public void coalesceFallsBackToIri() {
        Map<String, BindingSet> rows = byItem(rows(""
                + "SELECT ?item ?label WHERE {\n"
                + "  ?item a schema:Person .\n"
                + "  BIND(COALESCE(adp:prefLangLiteral(?item, schema:description, \"en\"), STR(?item)) AS ?label)\n"
                + "}"));
        assertLabel(rows, "person1", "label", "person1 english description", "en");
        assertLabel(rows, "person6", "label", "http://example.com/person6", null);
    }

    // ---- other usage ----

    @Test
    public void worksAsSelectExpression() {
        Map<String, BindingSet> rows = byItem(rows(""
                + "SELECT ?item (adp:prefLangLiteral(?item, schema:name, \"fr\") AS ?label) WHERE {\n"
                + "  ?item a schema:Person .\n"
                + "}"));
        assertEquals(7, rows.size());
        assertLabel(rows, "person1", "label", "person1 name in french", "fr");
        assertLabel(rows, "person2", "label", "person2 name in french", "fr");
    }

    @Test
    public void badArgumentsGiveUnbound() {
        Map<String, BindingSet> rows = byItem(rows(""
                + "SELECT ?item ?a ?b WHERE {\n"
                + "  ?item a schema:Person .\n"
                + "  BIND(adp:prefLangLiteral(?item) AS ?a)\n"                     // missing property
                + "  BIND(adp:prefLangLiteral(?item, \"schema:name\") AS ?b)\n"    // property is a string, not an IRI
                + "}"));
        assertEquals(7, rows.size());
        assertNull(rows.get("person1").getValue("a"));
        assertNull(rows.get("person1").getValue("b"));
    }

    @Test
    public void writesNothing() {
        long before;
        try (RepositoryConnection connection = getRepository().getConnection()) {
            before = connection.size();
        }
        labels("schema:sameAs", "");
        try (RepositoryConnection connection = getRepository().getConnection()) {
            assertEquals(before, connection.size());
        }
    }
}
