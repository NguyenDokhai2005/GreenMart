package vn.greenmart.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public class LoginClientIpResolver {
    private final List<IpAddressMatcher> networks;
    private final List<String> hosts;

    public LoginClientIpResolver(@Value("${greenmart.security.login.trusted-proxies:}") String proxies) {
        var entries = Arrays.stream(proxies.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        networks = entries.stream().filter(value -> value.contains("/")).map(IpAddressMatcher::new).toList();
        hosts = entries.stream().filter(value -> !value.contains("/")).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (peer == null) return "unknown";
        if (trusted(peer)) {
            // Nginx overwrites X-Real-IP with its socket peer, including for forged client headers.
            String forwarded = literal(request.getHeader("X-Real-IP"));
            if (forwarded != null) return forwarded;
        }
        return peer;
    }

    private boolean trusted(String peer) {
        if (networks.stream().anyMatch(network -> network.matches(peer))) return true;
        for (String host : hosts) {
            try {
                // Resolve configured service names at request time: frontend starts after backend in Compose.
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.getHostAddress().equals(peer)) return true;
                }
            } catch (UnknownHostException ignored) {
                // An unavailable configured proxy never grants trust to its forwarding headers.
            }
        }
        return false;
    }

    private static String literal(String input) {
        if (input == null || input.length() > 128) return null;
        String value = input.trim().split("%", 2)[0];
        if (!value.matches("[0-9a-fA-F:.]+")
                || (!value.contains(":") && !value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) return null;
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
