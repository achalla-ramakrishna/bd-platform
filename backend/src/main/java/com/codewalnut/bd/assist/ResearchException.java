package com.codewalnut.bd.assist;

/** Research failed in a way the user should see, with a plain message. */
class ResearchException extends RuntimeException {

    ResearchException(String message) {
        super(message);
    }
}
