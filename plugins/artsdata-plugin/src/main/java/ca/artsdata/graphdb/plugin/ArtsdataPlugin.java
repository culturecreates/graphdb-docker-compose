package ca.artsdata.graphdb.plugin;

import com.ontotext.trree.sdk.InitReason;
import com.ontotext.trree.sdk.PluginBase;
import com.ontotext.trree.sdk.PluginConnection;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;

/**
 * GraphDB plugin that registers Artsdata's SPARQL functions when GraphDB starts. Currently one function:
 * {@link PrefLangLiteralFunction} ({@code adp:prefLangLiteral}).
 *
 * <p>GraphDB loads each plugin under {@code lib/plugins} in its own class loader, so a function in a plugin jar
 * is not found by RDF4J's service loader on its own. Registering it here makes it available to all queries.
 */
public class ArtsdataPlugin extends PluginBase {

    @Override
    public String getName() {
        return "artsdata-plugin";
    }

    @Override
    public void initialize(InitReason reason, PluginConnection pluginConnection) {
        PrefLangLiteralFunction function = new PrefLangLiteralFunction();
        FunctionRegistry registry = FunctionRegistry.getInstance();
        registry.get(PrefLangLiteralFunction.URI).ifPresent(registry::remove);
        registry.add(function);

        getLogger().info("Artsdata plugin initialized: function <{}>, default language order {}",
                PrefLangLiteralFunction.URI, function.getDefaultLanguages());
    }
}
