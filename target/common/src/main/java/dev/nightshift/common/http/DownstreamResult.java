package dev.nightshift.common.http;

/** Outcome of one call to another service: an HTTP status and body, or a transport failure. */
public record DownstreamResult(int status, String body, Failure failure, String detail) {

    public enum Failure { NONE, TIMEOUT, UNREACHABLE }

    public boolean isSuccess() {
        return failure == Failure.NONE && status >= 200 && status < 300;
    }

    public boolean isClientError() {
        return failure == Failure.NONE && status >= 400 && status < 500;
    }

    public boolean isServerError() {
        return failure == Failure.NONE && status >= 500;
    }

    /** Short human-readable description for log lines and error bodies. */
    public String describe() {
        return failure == Failure.NONE ? "HTTP " + status : failure.name().toLowerCase() + " (" + detail + ")";
    }
}
