package ca.artsdata.graphdb.label;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LanguageRankingTest {

    private final LanguageRanking ranking = new LanguageRanking(List.of("en", "FR ", "", "en"));

    private static boolean better(LanguageRanking.Rank a, LanguageRanking.Rank b) {
        return a.compareTo(b) < 0;
    }

    @Test
    public void normalizesLanguageOrder() {
        assertEquals(List.of("en", "fr"), ranking.getLanguageOrder());
    }

    @Test
    public void parsesSpacesAndCommas() {
        assertEquals(List.of("en", "fr", "de"), LanguageRanking.parse(" en  fr,de "));
        assertEquals(List.of(), LanguageRanking.parse(""));
        assertEquals(List.of(), LanguageRanking.parse(null));
    }

    @Test
    public void requestedLanguagesComeBeforeDefaults() {
        assertEquals(List.of("fr", "de", "en"),
                LanguageRanking.of(List.of("fr", "de"), List.of("en", "fr")).getLanguageOrder());
    }

    @Test
    public void earlierLanguageWins() {
        assertTrue(better(ranking.languageTagged("b", "en"), ranking.languageTagged("a", "fr")));
    }

    @Test
    public void tagsAreCaseInsensitive() {
        assertTrue(better(ranking.languageTagged("b", "EN"), ranking.languageTagged("a", "fr")));
    }

    @Test
    public void regionalTagMatchesButExactWins() {
        LanguageRanking.Rank frCa = ranking.languageTagged("a", "fr-CA");
        assertTrue(better(ranking.languageTagged("z", "fr"), frCa));
        assertTrue(better(frCa, ranking.plainString("a")));
        assertEquals(2, ranking.languageTagged("x", "fra").tier); // "fra" is not "fr"
    }

    @Test
    public void tierOrder() {
        LanguageRanking.Rank fr = ranking.languageTagged("e", "fr");
        LanguageRanking.Rank plain = ranking.plainString("d");
        LanguageRanking.Rank es = ranking.languageTagged("c", "es");
        LanguageRanking.Rank number = ranking.otherLiteral("b");
        LanguageRanking.Rank bnode = ranking.other("a");
        assertTrue(better(fr, plain));
        assertTrue(better(plain, es));
        assertTrue(better(es, number));
        assertTrue(better(number, bnode));
    }

    @Test
    public void tiesAreDeterministic() {
        assertTrue(better(ranking.languageTagged("Anne", "en"), ranking.languageTagged("Zoé", "en")));
        assertTrue(better(ranking.languageTagged("Same", "de"), ranking.languageTagged("Same", "it")));
    }
}
