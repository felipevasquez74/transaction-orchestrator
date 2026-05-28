package co.tumipay.orchestrator.infrastructure.inbound.http.filter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	public static final String API_KEY_HEADER = "X-API-Key";

	private final Set<String> validApiKeys;

	private final Map<String, List<String>> keyIpWhitelist;

	public ApiKeyAuthFilter(@Value("${tumipay.orchestrator.security.api-keys}") String apiKeysConfig,
			@Value("#{${tumipay.orchestrator.security.ip-whitelist:{}}}") Map<String, String> ipWhitelistConfig) {

		this.validApiKeys = Arrays.stream(apiKeysConfig.split(",")).map(String::trim)
				.collect(Collectors.toUnmodifiableSet());

		Map<String, List<String>> parsed = new HashMap<>();
		if (ipWhitelistConfig != null) {
			ipWhitelistConfig.forEach((key, prefixes) -> parsed.put(key.trim(), List.of(prefixes.split(","))));
		}
		this.keyIpWhitelist = Map.copyOf(parsed);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String clientIp = MDC.get(CorrelationIdFilter.MDC_CLIENT_IP);
		if (clientIp == null) {
			clientIp = request.getRemoteAddr();
		}

		String apiKey = request.getHeader(API_KEY_HEADER);

		if (apiKey == null || apiKey.isBlank()) {
			log.warn("security.missing_api_key path={} ip={}", request.getRequestURI(), clientIp);
			writeUnauthorized(response, "Missing X-API-Key header");
			return;
		}

		String matchedKey = validApiKeys.stream().filter(key -> constantTimeEquals(key, apiKey)).findFirst()
				.orElse(null);

		if (matchedKey == null) {
			log.warn("security.invalid_api_key path={} ip={}", request.getRequestURI(), clientIp);
			writeUnauthorized(response, "Invalid API key");
			return;
		}

		if (!isIpAllowed(matchedKey, clientIp)) {
			log.warn("security.ip_not_whitelisted path={} ip={}", request.getRequestURI(), clientIp);
			writeUnauthorized(response, "Invalid API key");
			return;
		}

		UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(matchedKey, null,
				List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
		SecurityContextHolder.getContext().setAuthentication(auth);

		filterChain.doFilter(request, response);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.startsWith("/actuator/health") || path.startsWith("/actuator/info") || path.startsWith("/api-docs")
				|| path.startsWith("/swagger-ui");
	}

	private boolean isIpAllowed(String apiKey, String clientIp) {
		List<String> allowedPrefixes = keyIpWhitelist.get(apiKey);
		if (allowedPrefixes == null || allowedPrefixes.isEmpty()) {
			return true; // No whitelist configured → open to any IP
		}
		return allowedPrefixes.stream().map(String::trim).anyMatch(clientIp::startsWith);
	}

	private boolean constantTimeEquals(String a, String b) {
		if (a.length() != b.length())
			return false;
		int result = 0;
		for (int i = 0; i < a.length(); i++) {
			result |= a.charAt(i) ^ b.charAt(i);
		}
		return result == 0;
	}

	private void writeUnauthorized(HttpServletResponse response, String reason) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write(String.format("{\"code\":\"401\",\"message\":\"%s\",\"data\":null}", reason));
	}

}
