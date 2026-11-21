package com.codewalnut.bd.assist;

import java.util.List;

import com.codewalnut.bd.reporting.ProspectContextQueries;

/**
 * The BD agent's research step (ADR-0022): researches one person on the public web (never LinkedIn pages) and in our
 * records, and writes a brief a person reads before reaching out. Nothing is sent to anyone.
 */
public interface ProspectResearcher {

    /** A statement and where it came from: a web address, or "Our records". */
    record Signal(String statement, String source) {}

    record Angle(String offer, String why) {}

    /**
     * {@code roleFit} and {@code timing}: 1 to 5 with reasons, judged by the model; the app adds access and the score.
     * {@code connectionNote}: at most 280 characters, for a person to edit and send.
     */
    record Brief(String headline, String role, String summary, List<Signal> whyNow, Angle angle, List<String> talkingPoints,
                 String connectionNote, List<String> cautions, int roleFit, String roleFitReason, int timing, String timingReason) {}

    record Source(String url, String title) {}

    record Result(Brief brief, List<Source> sources, String model) {}

    boolean enabled();

    Result research(ProspectContextQueries.Context context);
}
