package vn.greenmart.common.exception;

public class RateLimitException extends AppException {
    private final long retryAfterSeconds;

    public RateLimitException(ErrorCode code, long retryAfterSeconds) {
        super(code);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
