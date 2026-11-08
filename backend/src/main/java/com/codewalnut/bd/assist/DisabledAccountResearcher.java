package com.codewalnut.bd.assist;

import com.codewalnut.bd.reporting.ResearchContextQueries;

/** Used when no Anthropic API key is set. */
class DisabledAccountResearcher implements AccountResearcher {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Result research(ResearchContextQueries.Context context) {
        throw new ResearchException("Account research is not switched on. Ask an Admin to set ANTHROPIC_API_KEY.");
    }
}
