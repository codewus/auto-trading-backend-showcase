package com.codewus.showcase.order.model;

public class ActionConflictException extends RuntimeException {
    public ActionConflictException() {
        super("actionId already belongs to a different request");
    }
}
