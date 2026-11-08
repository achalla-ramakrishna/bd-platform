package com.codewalnut.bd.assist;

import java.util.List;

/**
 * Reads one LinkedIn profile that a person copied or screenshotted and suggests contact fields. A
 * person always checks and saves the result; nothing is read from or sent to LinkedIn (ADR-0005,
 * ADR-0009).
 */
public interface ProfileReader {

    /** What the user pasted: the profile address, the copied page text, and/or a screenshot. */
    record Request(String linkedinUrl, String text, byte[] image, String imageMediaType, List<String> personaCodes) {}

    /** Suggested fields. Empty strings mean "not shown on the profile". */
    record Suggestion(String fullName, String title, String company, String city, String persona,
                      String personaReason, String notes) {}

    boolean enabled();

    Suggestion read(Request request);
}
