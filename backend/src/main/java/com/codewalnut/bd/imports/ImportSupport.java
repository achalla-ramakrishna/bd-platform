package com.codewalnut.bd.imports;

import java.util.Map;

import com.codewalnut.bd.accounts.AccountKey;

/** Checks shared by several importers. */
final class ImportSupport {

    private ImportSupport() {}

    /** Resolves account_website_domain + account_city to an existing account id, or records an error. */
    static Long account(RowReader r, Map<AccountKey, Long> accountIds) {
        String domain = r.text("account_website_domain", true, 200);
        String city = r.text("account_city", true, 80);
        if (domain == null || city == null) {
            return null;
        }
        AccountKey key = new AccountKey(domain, city);
        Long id = accountIds.get(key);
        if (id == null) {
            r.error("account_website_domain", "No account with domain " + key.domain() + " in " + city
                + ". Upload it in 1-accounts first.");
        }
        return id;
    }
}
