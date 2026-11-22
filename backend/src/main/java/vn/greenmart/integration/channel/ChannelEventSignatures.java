package vn.greenmart.integration.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

/** Versioned envelope for order events; order intake/callback signatures remain unchanged. */
@Component
@RequiredArgsConstructor
public class ChannelEventSignatures {
    private final ChannelSignatures signatures;
    private final Clock clock;

    public record Verified(UUID eventId, String requestHash) {}

    public Verified verify(String channel, String method, String path, byte[] body,
            String eventId, String timestamp, String signature) {
        if (eventId == null || !eventId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > 300
                || !signatures.valid(channel, canonical(method, path, eventId, timestamp, body), signature)) {
            throw new AppException(ErrorCode.INVALID_SIGNATURE);
        }
        // A retry signs a fresh transport timestamp, but must retain the exact target and body.
        String fingerprint = sha256((method + "\n" + path + "\n" + sha256(body)).getBytes(StandardCharsets.UTF_8));
        return new Verified(UUID.fromString(eventId), fingerprint);
    }

    public static byte[] canonical(String method, String path, String eventId, String timestamp, byte[] body) {
        return ("GREENMART-EVENT-V1\n" + method + "\n" + path + "\n" + eventId + "\n" + timestamp
                + "\n" + sha256(body)).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 không khả dụng", ex);
        }
    }
}
