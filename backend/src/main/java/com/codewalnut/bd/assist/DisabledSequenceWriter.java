package com.codewalnut.bd.assist;

/** Used when no Anthropic API key is set. */
class DisabledSequenceWriter implements SequenceWriter {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Result write(Request request) {
        throw new ResearchException("The email writer is not switched on. Ask an Admin to set ANTHROPIC_API_KEY.");
    }
}
