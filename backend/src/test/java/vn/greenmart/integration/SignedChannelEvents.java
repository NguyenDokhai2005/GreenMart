package vn.greenmart.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import vn.greenmart.integration.channel.ChannelEventSignatures;
import vn.greenmart.integration.channel.ChannelSignatures;

public final class SignedChannelEvents {
    private SignedChannelEvents() {}
    public static HttpHeaders headers(ChannelSignatures signatures, String channel, String path, String body) {
        return headers(signatures, channel, path, body, UUID.randomUUID().toString(), Long.toString(Instant.now().getEpochSecond()));
    }
    public static HttpHeaders headers(ChannelSignatures signatures, String channel, String path, String body,
            String eventId, String timestamp) {
        var headers = new HttpHeaders();
        headers.set("X-Event-ID", eventId);
        headers.set("X-Event-Timestamp", timestamp);
        headers.set("X-Signature", signatures.sign(channel, ChannelEventSignatures.canonical(
                "POST", path, eventId, timestamp, body.getBytes(StandardCharsets.UTF_8))));
        return headers;
    }
}
