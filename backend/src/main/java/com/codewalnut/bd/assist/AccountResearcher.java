package com.codewalnut.bd.assist;

import java.util.List;

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

    record Summary(String centreOverview, List<Fact> facts, List<Fact> buyingSignals, List<String> likelyNeeds,
                   List<Offer> recommendedOffers, List<Target> whoToContact, String openingMessage,
                   List<String> talkingPoints, List<String> cautions) {}

    record Source(String url, String title) {}

    record Result(Summary summary, List<Source> sources, String model) {}

    boolean enabled();

    Result research(ResearchContextQueries.Context context);
}
