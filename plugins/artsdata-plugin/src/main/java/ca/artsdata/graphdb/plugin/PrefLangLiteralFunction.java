package ca.artsdata.graphdb.plugin;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;

import java.util.ArrayList;
import java.util.List;

/**
 * SPARQL function {@code adp:prefLangLiteral}: the best-language value of a property.
 *
 * <pre>
 *   PREFIX schema: &lt;http://schema.org/&gt;
 *   PREFIX adp: &lt;http://kg.artsdata.ca/plugin#&gt;
 *
 *   SELECT ?item ?nameLabel ?descriptionLabel WHERE {
 *     ?item a schema:Person .
 *     BIND(adp:prefLangLiteral(?item, schema:name, "en fr") AS ?nameLabel)
 *     BIND(adp:prefLangLiteral(?item, schema:description, "en fr") AS ?descriptionLabel)
 *   }
 * </pre>
 *
 * Arguments:
 * <ol>
 *   <li>the subject;</li>
 *   <li>the property (any IRI);</li>
 *   <li>optional languages in priority order: one string ({@code "en fr"}, {@code "en,fr"}) or several
 *       ({@code "en", "fr"}). They come before the default order from {@code -Dlabel.languages}.</li>
 * </ol>
 * Returns the best value by {@link LanguageRanking}. An IRI value is returned as a plain string. If the subject
 * has no value for the property, the result is unbound; to fall back to the IRI, use
 * {@code COALESCE(adp:prefLangLiteral(...), STR(?item))}.
 *
 * <p>The function reads the repository through the {@link TripleSource} that RDF4J passes to
 * {@link #evaluate(TripleSource, Value...)}.
 */
public class PrefLangLiteralFunction implements Function {

    public static final String NAMESPACE = "http://kg.artsdata.ca/plugin#";
    public static final String URI = NAMESPACE + "prefLangLiteral";

    /** JVM system property with the default language order, e.g. {@code -Dlabel.languages=en,fr}. */
    public static final String LANGUAGES_PROPERTY = "label.languages";
    public static final String DEFAULT_LANGUAGES = "en,fr";

    private final List<String> defaultLanguages;

    /** Reads the default language order from {@value #LANGUAGES_PROPERTY}. */
    public PrefLangLiteralFunction() {
        this(LanguageRanking.parse(System.getProperty(LANGUAGES_PROPERTY, DEFAULT_LANGUAGES)));
    }

    public PrefLangLiteralFunction(List<String> defaultLanguages) {
        this.defaultLanguages = List.copyOf(defaultLanguages);
    }

    public List<String> getDefaultLanguages() {
        return defaultLanguages;
    }

    @Override
    public String getURI() {
        return URI;
    }

    @Override
    public Value evaluate(TripleSource tripleSource, Value... args) throws ValueExprEvaluationException {
        if (args.length < 2) {
            throw new ValueExprEvaluationException(
                    "adp:prefLangLiteral expects (?subject, property [, \"en fr\"]), got "
                            + args.length + " argument(s)");
        }
        if (!(args[0] instanceof Resource)) {
            throw new ValueExprEvaluationException(
                    "adp:prefLangLiteral: the first argument must be the subject, got " + args[0]);
        }
        if (!(args[1] instanceof IRI)) {
            throw new ValueExprEvaluationException(
                    "adp:prefLangLiteral: the second argument must be a property IRI, got " + args[1]);
        }
        LanguageRanking ranking = LanguageRanking.of(requestedLanguages(args), defaultLanguages);

        Value best = bestValue(tripleSource, (Resource) args[0], (IRI) args[1], ranking);
        if (best == null) {
            // No value: unbound result (an error in a function expression leaves the BIND variable unbound)
            throw new ValueExprEvaluationException("adp:prefLangLiteral: no value for " + args[1] + " on " + args[0]);
        }
        return asResult(best, tripleSource.getValueFactory());
    }

    /** Only called if the query engine provides no repository access; the function cannot work without it. */
    @Override
    @Deprecated
    public Value evaluate(ValueFactory valueFactory, Value... args) throws ValueExprEvaluationException {
        throw new ValueExprEvaluationException("adp:prefLangLiteral needs repository access, which was not provided");
    }

    private static List<String> requestedLanguages(Value[] args) {
        List<String> languages = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (!(args[i] instanceof Literal)) {
                throw new ValueExprEvaluationException(
                        "adp:prefLangLiteral: languages must be strings such as \"en fr\", got " + args[i]);
            }
            languages.addAll(LanguageRanking.parse(args[i].stringValue()));
        }
        return languages;
    }

    private static Value bestValue(TripleSource tripleSource, Resource subject, IRI property,
                                   LanguageRanking ranking) {
        Value best = null;
        LanguageRanking.Rank bestRank = null;
        var statements = tripleSource.getStatements(subject, property, null);
        try {
            while (statements.hasNext()) {
                Value candidate = statements.next().getObject();
                LanguageRanking.Rank rank = rank(candidate, ranking);
                if (bestRank == null || rank.compareTo(bestRank) < 0) {
                    best = candidate;
                    bestRank = rank;
                }
            }
        } finally {
            statements.close();
        }
        return best;
    }

    private static LanguageRanking.Rank rank(Value value, LanguageRanking ranking) {
        if (value instanceof Literal) {
            Literal literal = (Literal) value;
            if (literal.getLanguage().isPresent()) {
                return ranking.languageTagged(literal.getLabel(), literal.getLanguage().get());
            }
            if (XSD.STRING.equals(literal.getDatatype())) {
                return ranking.plainString(literal.getLabel());
            }
            return ranking.otherLiteral(literal.getLabel());
        }
        if (value instanceof IRI) {
            return ranking.plainString(value.stringValue());
        }
        return ranking.other(value.stringValue());
    }

    /** IRIs are returned as plain strings; everything else as is. */
    private static Value asResult(Value value, ValueFactory valueFactory) {
        return value instanceof IRI ? valueFactory.createLiteral(value.stringValue()) : value;
    }
}
