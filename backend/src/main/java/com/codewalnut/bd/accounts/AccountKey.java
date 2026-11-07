package com.codewalnut.bd.accounts;

import java.util.Locale;

/** An account's natural key: website domain + city (BR-BD-01). Both are normalised. */
public record AccountKey(String domain, String city) {

    public AccountKey {
        domain = normaliseDomain(domain);
        city = city == null ? "" : city.trim().toLowerCase(Locale.ROOT);
    }

    /** "https://www.Example.com/careers" becomes "example.com". */
    public static String normaliseDomain(String raw) {
        if (raw == null) {
            return "";
        }
        String d = raw.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^[a-z]+://", "");
        d = d.replaceFirst("^www\\.", "");
        int slash = d.indexOf('/');
        if (slash >= 0) {
            d = d.substring(0, slash);
        }
        return d;
    }

    public static boolean isValidDomain(String normalised) {
        return normalised.matches("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");
    }

    @Override
    public String toString() {
        return domain + " / " + city;
    }
}
