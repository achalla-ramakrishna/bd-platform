package com.codewalnut.bd.assist;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

import com.codewalnut.bd.reporting.ResearchContextQueries;

/**
 * Researches one account on the public web and suggests how CodeWalnut can help (ADR-0010). The
 * result is advice for a person to read; nothing is sent to anyone.
 */
public interface AccountResearcher {

    /** A statement and where it came from: a web address, or "Our records". */
    record Fact(String statement, String source) {}

    record Offer(String offer, String why) {}

    record Target(String persona, String why, String knownContact) {}

    /** How well the account fits CodeWalnut, judged from the research (ADR-0012). Feeds the fit score. */
    enum FitRating {
        STRONG, MEDIUM, WEAK;

        /** Accepts any letter case, so hand-written uploads can say "strong". Unknown text is an error. */
        @JsonCreator
        static FitRating parse(String value) {
            return value == null || value.isBlank() ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    record Summary(String centreOverview, List<Fact> facts, List<Fact> buyingSignals, List<String> likelyNeeds,
                   List<Offer> recommendedOffers, List<Target> whoToContact, String openingMessage,
                   List<String> talkingPoints, List<String> cautions, FitRating fitRating) {}

    record Source(String url, String title) {}

    record Result(Summary summary, List<Source> sources, String model) {}

    boolean enabled();

    Result research(ResearchContextQueries.Context context);
}
