package com.codewalnut.bd.assist;

import java.util.List;

import com.codewalnut.bd.shared.RuleViolationException;

/** Used when no Anthropic API key is set: the form works, filled in by hand. */
class DisabledProfileReader implements ProfileReader {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Suggestion read(Request request) {
        throw new RuleViolationException(List.of("Profile reading is not switched on. Fill in the fields by hand."));
    }
}
