package net.poena.actors.core;

/** A refusal with an HTTP status; the message is safe to show the caller. */
public final class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
