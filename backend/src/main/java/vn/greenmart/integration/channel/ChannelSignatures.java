package vn.greenmart.integration.channel;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

@Component
public class ChannelSignatures {
    private final JdbcTemplate jdbc;
    private final String webSecret;
    private final String marketSecret;

    public ChannelSignatures(JdbcTemplate jdbc, @Value("${CHANNEL_WEB_SECRET:}") String webSecret,
                             @Value("${CHANNEL_MARKET_SECRET:}") String marketSecret) {
        this.jdbc = jdbc;
        this.webSecret = webSecret;
        this.marketSecret = marketSecret;
    }

    private String secret(String channel) {
        String fallback = switch (channel) {
            case "WEB" -> webSecret;
            case "MARKET" -> marketSecret;
            default -> throw new AppException(ErrorCode.NOT_FOUND, "Kênh tích hợp không tồn tại");
        };
        return jdbc.query("SELECT webhook_secret FROM sales_channel WHERE code=?",
                (rs, row) -> rs.getString(1), channel).stream()
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse(fallback);
    }

    public String sign(String channel, byte[] body) {
        String key = secret(channel);
        if (key.isBlank() || key.equals("change-me")) {
            throw new IllegalStateException("Thiếu secret của kênh " + channel);
        }
        return HexFormat.of().formatHex(mac(key, body));
    }

    public boolean valid(String channel, byte[] body, String signature) {
        String key = secret(channel);
        if (key.isBlank() || key.equals("change-me") || signature == null) return false;
        String hex = signature.startsWith("sha256=") ? signature.substring(7) : signature;
        if (!hex.matches("[0-9a-fA-F]{64}")) return false;
        return MessageDigest.isEqual(mac(key, body), HexFormat.of().parseHex(hex));
    }

    private byte[] mac(String key, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Không thể tính chữ ký HMAC", ex);
        }
    }
}
