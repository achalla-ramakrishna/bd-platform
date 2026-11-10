package com.codewalnut.bd.reporting;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Points for each part of the account fit score (FR-ACC-04). Set under {@code app.fit} to change the
 * weighting without code changes. The defaults add up to 100 (ADR-0012).
 *
 * @param sizeByBand        points by headcount band; mid-size centres decide faster (2027-2028 plan)
 * @param sizeUnknown       points when the band is not recorded
 * @param perTechMatch      points per technology the account uses that our engineers know
 * @param techMax           cap on technology points
 * @param perBuyingSignal   points per buying signal in the latest research
 * @param signalsMax        cap on buying-signal points
 * @param researchFit       points by the fit rating in the latest research: STRONG, MEDIUM, WEAK
 * @param decisionContact   a centre head, engineering or product leader is in our contacts
 * @param anyContact        some other contact is in our contacts
 * @param positiveEngagement a reply or meeting in the last 90 days
 * @param anyEngagement     any logged touch in the last 90 days
 * @param industryMatch     we already have a client in the same industry
 */
@ConfigurationProperties("app.fit")
public record FitProperties(Map<String, Integer> sizeByBand, Integer sizeUnknown, Integer perTechMatch, Integer techMax,
                            Integer perBuyingSignal, Integer signalsMax, Integer decisionContact, Integer anyContact,
                            Integer positiveEngagement, Integer anyEngagement, Integer industryMatch,
                            Map<String, Integer> researchFit) {

    public FitProperties {
        sizeByBand = sizeByBand == null || sizeByBand.isEmpty()
            ? Map.of("<500", 12, "500-2000", 20, "2000-5000", 15, "5000+", 8) : sizeByBand;
        sizeUnknown = sizeUnknown == null ? 5 : sizeUnknown;
        perTechMatch = perTechMatch == null ? 5 : perTechMatch;
        techMax = techMax == null ? 10 : techMax;
        perBuyingSignal = perBuyingSignal == null ? 5 : perBuyingSignal;
        signalsMax = signalsMax == null ? 10 : signalsMax;
        decisionContact = decisionContact == null ? 15 : decisionContact;
        anyContact = anyContact == null ? 7 : anyContact;
        positiveEngagement = positiveEngagement == null ? 15 : positiveEngagement;
        anyEngagement = anyEngagement == null ? 5 : anyEngagement;
        industryMatch = industryMatch == null ? 10 : industryMatch;
        researchFit = researchFit == null || researchFit.isEmpty()
            ? Map.of("STRONG", 20, "MEDIUM", 10, "WEAK", 0) : researchFit;
    }
}
