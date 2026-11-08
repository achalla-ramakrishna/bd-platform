package com.codewalnut.bd.assist;

import java.util.List;

/** Used when no Anthropic API key is set. */
class DisabledGccFinder implements GccFinder {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Result find(String city, String industry, List<String> knownCompanies) {
        throw new ResearchException("Find GCCs is not switched on. Ask an Admin to set ANTHROPIC_API_KEY.");
    }
}
