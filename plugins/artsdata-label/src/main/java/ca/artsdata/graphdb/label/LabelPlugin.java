package ca.artsdata.graphdb.label;

import com.ontotext.trree.sdk.InitReason;
import com.ontotext.trree.sdk.PluginBase;
import com.ontotext.trree.sdk.PluginConnection;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;

/**
 * GraphDB plugin whose only job is to register the {@link LabelFunction} ({@code adp:label}) when GraphDB starts.
 *
 * <p>GraphDB loads each plugin under {@code lib/plugins} in its own class loader, so a function in a plugin jar
 * is not found by RDF4J's service loader on its own. Registering it here makes it available to all queries.
 */
public class LabelPlugin extends PluginBase {

    @Override
    public String getName() {
        return "artsdata-label";
    }

    @Override
    public void initialize(InitReason reason, PluginConnection pluginConnection) {
        LabelFunction function = new LabelFunction();
        FunctionRegistry registry = FunctionRegistry.getInstance();
        registry.get(LabelFunction.URI).ifPresent(registry::remove);
        registry.add(function);

        getLogger().info("Artsdata label plugin initialized: function <{}>, default language order {}",
                LabelFunction.URI, function.getDefaultLanguages());
    }
}
