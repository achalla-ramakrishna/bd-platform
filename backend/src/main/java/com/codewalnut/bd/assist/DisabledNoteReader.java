package com.codewalnut.bd.assist;

import java.util.List;

import com.codewalnut.bd.shared.RuleViolationException;

/** Used when ANTHROPIC_API_KEY is not set. */
class DisabledNoteReader implements NoteReader {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Reading read(Request request) {
        throw new RuleViolationException(List.of(
            "The assistant is not switched on. An Admin needs to set ANTHROPIC_API_KEY in Railway. "
                + "Meanwhile, log it on the Conversations or Connections page."));
    }
}
