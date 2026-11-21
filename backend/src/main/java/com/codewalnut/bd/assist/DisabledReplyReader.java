package com.codewalnut.bd.assist;

/** Used when no Anthropic API key is set: replies are handled by hand. */
class DisabledReplyReader implements ReplyReader {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Reading read(Request request) {
        throw new ResearchException("The reply agent is not switched on. Ask an Admin to set ANTHROPIC_API_KEY.");
    }
}
