package com.codewalnut.bd.assist;

import java.util.List;

/** Finds GCCs in a city, optionally in one industry, that are not already accounts (ADR-0011). */
public interface GccFinder {

    record Candidate(String company, String parentCompany, String hqCountry, String city, String websiteDomain,
                     String industry, String description, String source) {}

    record Result(List<Candidate> candidates, String model) {}

    boolean enabled();

    Result find(String city, String industry, List<String> knownCompanies);
}
