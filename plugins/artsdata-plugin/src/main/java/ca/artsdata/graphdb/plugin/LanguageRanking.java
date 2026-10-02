package ca.artsdata.graphdb.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ranks candidate values by language.
 *
 * <p>Order, best first:
 * <ol>
 *   <li>A language from the configured order; an earlier language wins, and an exact tag ({@code fr}) beats a
 *       regional one ({@code fr-CA}) at the same position. Tags are compared case-insensitively.</li>
 *   <li>An untagged string, or an IRI (ranked by its IRI text).</li>
 *   <li>Any other language.</li>
 *   <li>Any other literal (e.g. a number or a date).</li>
 *   <li>Anything else (blank nodes).</li>
 * </ol>
 * Ties are broken by text, then language tag, so the result is the same on every run.
 */
public final class LanguageRanking {

    private final List<String> languageOrder;

    /** @param languageOrder languages in priority order; blanks and duplicates are ignored */
    public LanguageRanking(List<String> languageOrder) {
        List<String> normalized = new ArrayList<>();
        for (String lang : languageOrder) {
            String l = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
            if (!l.isEmpty() && !normalized.contains(l)) {
                normalized.add(l);
            }
        }
        this.languageOrder = Collections.unmodifiableList(normalized);
    }

    /** Requested languages first, then the defaults. */
    public static LanguageRanking of(List<String> requested, List<String> defaults) {
        List<String> all = new ArrayList<>(requested);
        all.addAll(defaults);
        return new LanguageRanking(all);
    }

    /** Splits {@code "en fr"} or {@code "en,fr"} into {@code [en, fr]}. */
    public static List<String> parse(String languages) {
        List<String> result = new ArrayList<>();
        if (languages != null) {
            for (String lang : languages.split("[\\s,]+")) {
                if (!lang.isEmpty()) {
                    result.add(lang);
                }
            }
        }
        return result;
    }

    public List<String> getLanguageOrder() {
        return languageOrder;
    }

    /** A literal with a language tag. */
    public Rank languageTagged(String text, String language) {
        String tag = language.toLowerCase(Locale.ROOT);
        for (int i = 0; i < languageOrder.size(); i++) {
            String wanted = languageOrder.get(i);
            if (tag.equals(wanted)) {
                return new Rank(0, i, 0, text, tag);
            }
            if (tag.startsWith(wanted + "-")) {
                return new Rank(0, i, 1, text, tag);
            }
        }
        return new Rank(2, 0, 0, text, tag);
    }

    /** An untagged string (xsd:string), or an IRI ranked by its text. */
    public Rank plainString(String text) {
        return new Rank(1, 0, 0, text, "");
    }

    /** Any other literal, e.g. a number or a date. */
    public Rank otherLiteral(String text) {
        return new Rank(3, 0, 0, text, "");
    }

    /** Anything else, e.g. a blank node. */
    public Rank other(String text) {
        return new Rank(4, 0, 0, text, "");
    }

    /** Comparable score; smaller is better. */
    public static final class Rank implements Comparable<Rank> {
        final int tier;
        final int position;
        final int exactness;
        final String text;
        final String language;

        Rank(int tier, int position, int exactness, String text, String language) {
            this.tier = tier;
            this.position = position;
            this.exactness = exactness;
            this.text = text == null ? "" : text;
            this.language = language;
        }

        @Override
        public int compareTo(Rank o) {
            int c = Integer.compare(tier, o.tier);
            if (c == 0) c = Integer.compare(position, o.position);
            if (c == 0) c = Integer.compare(exactness, o.exactness);
            if (c == 0) c = text.compareTo(o.text);
            if (c == 0) c = language.compareTo(o.language);
            return c;
        }

        @Override
        public String toString() {
            return "Rank{tier=" + tier + ", pos=" + position + ", exact=" + exactness
                    + ", text='" + text + "', lang='" + language + "'}";
        }
    }
}
