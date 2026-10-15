package com.example.repolens.service;

public class CloneException extends RuntimeException {

    public CloneException(String message) {
        super(message);
    }

    public CloneException(String message, Throwable cause) {
        super(message, cause);
    }
}