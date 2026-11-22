package com.codewalnut.bd.assist;

/** Used when no Anthropic API key is set. */
class DisabledCallPrepWriter implements CallPrepWriter {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Result write(Request request) {
        throw new ResearchException("The call-prep agent is not switched on. Ask an Admin to set ANTHROPIC_API_KEY.");
    }
}
