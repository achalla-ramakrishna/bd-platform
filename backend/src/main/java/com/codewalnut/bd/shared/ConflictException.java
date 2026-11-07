package com.codewalnut.bd.shared;

/** Someone else changed the record since it was loaded (optimistic locking, ADR-0008). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
