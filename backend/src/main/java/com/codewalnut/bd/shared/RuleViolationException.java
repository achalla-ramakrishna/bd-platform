package com.codewalnut.bd.shared;

import java.util.List;

/**
 * A request broke one or more business rules (for example BR-BD-07). Returned as HTTP 422 with every
 * message, so the user can fix them all at once.
 */
public class RuleViolationException extends RuntimeException {

    private final List<String> problems;

    public RuleViolationException(List<String> problems) {
        super(String.join(" ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
