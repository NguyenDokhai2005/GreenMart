package vn.greenmart.common.logging;

import java.util.UUID;
import org.slf4j.MDC;

public final class TraceContext {
    public static final String HEADER = "X-Correlation-ID";
    private TraceContext() {}
    public static String normalize(Object value) {
        return value instanceof String id && id.matches("[A-Za-z0-9_-]{1,64}")
                ? id : UUID.randomUUID().toString();
    }
    public static String current() { return normalize(MDC.get("traceId")); }
}
