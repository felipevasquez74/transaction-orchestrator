package co.tumipay.orchestrator.infrastructure.security;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SsrfGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "ip6-localhost",
            "ip6-loopback",
            "0.0.0.0",
            "[::]",
            "::1",
            "::0"
    );

    private static final Pattern BLOCKED_IP_PATTERN = Pattern.compile(
            "^(" +
            "127\\." +
            "|10\\." +
            "|172\\.(1[6-9]|2[0-9]|3[01])\\." +
            "|192\\.168\\." +
            "|169\\.254\\." +
            "|0\\.0\\.0\\.0" +
            "|100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\." +
            "|fd[0-9a-fA-F]{2}:" +
            "|fe80:" +
            ")"
    );

    public String validateSafety(String urlString, String fieldName) {
        if (urlString == null || urlString.isBlank()) {
            return null; 
        }

        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            return null; 
        }

        String scheme = url.getProtocol().toLowerCase();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            log.warn("ssrf.blocked_scheme field={} scheme={}", fieldName, scheme);
            return String.format(
                    "Field '%s' uses a disallowed scheme '%s'. Only http/https are permitted.",
                    fieldName, scheme);
        }

        String host = url.getHost().toLowerCase().trim();

        if (BLOCKED_HOSTNAMES.contains(host)) {
            log.warn("ssrf.blocked_hostname field={} host={}", fieldName, host);
            return String.format(
                    "Field '%s' points to a restricted host '%s'.",
                    fieldName, host);
        }

        if (BLOCKED_IP_PATTERN.matcher(host).find()) {
            log.warn("ssrf.blocked_private_ip field={} host={}", fieldName, host);
            return String.format(
                    "Field '%s' points to a private or reserved IP address. " +
                    "Only publicly routable endpoints are accepted.",
                    fieldName);
        }

        return null;
    }
}
