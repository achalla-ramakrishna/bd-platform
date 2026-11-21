package com.codewalnut.bd.assist;

/** An agent step failed in a way the user should see, with a plain message (other modules show it as is). */
public class ResearchException extends RuntimeException {

    ResearchException(String message) {
        super(message);
    }
}
