package com.codewalnut.bd.shared;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super(what + " " + id + " was not found");
    }
}
