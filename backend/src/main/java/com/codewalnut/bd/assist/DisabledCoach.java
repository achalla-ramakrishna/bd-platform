package com.codewalnut.bd.assist;

import java.util.List;

import com.codewalnut.bd.shared.RuleViolationException;

/** Used when ANTHROPIC_API_KEY is not set: Walnut still writes morning briefs, but cannot chat. */
class DisabledCoach implements Coach {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Reply reply(Request request) {
        throw new RuleViolationException(List.of(
            "Walnut can write your morning plan, but chatting needs Claude. An Admin needs to set ANTHROPIC_API_KEY in Railway."));
    }
}
