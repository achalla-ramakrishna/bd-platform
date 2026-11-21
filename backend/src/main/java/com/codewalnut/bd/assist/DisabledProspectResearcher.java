package com.codewalnut.bd.assist;

import com.codewalnut.bd.reporting.ProspectContextQueries;

/** Used when no Anthropic API key is set. */
class DisabledProspectResearcher implements ProspectResearcher {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Result research(ProspectContextQueries.Context context) {
        throw new ResearchException("The research agent is not switched on. Ask an Admin to set ANTHROPIC_API_KEY.");
    }
}
