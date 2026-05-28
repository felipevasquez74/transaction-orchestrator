package co.tumipay.orchestrator.infrastructure.inbound.http.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("ApiKeyAuthFilter Unit Tests")
class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter("test-key-alpha,test-key-beta", new HashMap<>());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Allows request with a valid API key and sets Security context")
    void allowsRequest_withValidApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "test-key-alpha");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("Rejects request when X-API-Key header is absent (401)")
    void rejectsRequest_whenApiKeyMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Missing X-API-Key header");
    }

    @Test
    @DisplayName("Rejects request when API key is blank (401)")
    void rejectsRequest_whenApiKeyIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Rejects request with an unrecognized API key (401)")
    void rejectsRequest_withInvalidApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid API key");
    }

    @Test
    @DisplayName("Rejects request from IP not in whitelist (401)")
    void rejectsRequest_whenIpNotWhitelisted() throws Exception {
        Map<String, String> whitelist = new HashMap<>();
        whitelist.put("test-key-alpha", "10.0.0.");
        ApiKeyAuthFilter filterWithWhitelist = new ApiKeyAuthFilter("test-key-alpha", whitelist);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "test-key-alpha");
        request.setRemoteAddr("192.168.1.50");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filterWithWhitelist.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Allows request from whitelisted IP prefix")
    void allowsRequest_fromWhitelistedIp() throws Exception {
        Map<String, String> whitelist = new HashMap<>();
        whitelist.put("test-key-alpha", "10.0.0.");
        ApiKeyAuthFilter filterWithWhitelist = new ApiKeyAuthFilter("test-key-alpha", whitelist);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "test-key-alpha");
        request.setRemoteAddr("10.0.0.55");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filterWithWhitelist.doFilter(request, response, chain);

        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("shouldNotFilter returns true for actuator/health")
    void shouldNotFilter_forActuatorHealth() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter returns true for swagger-ui")
    void shouldNotFilter_forSwaggerUi() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/swagger-ui/index.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter returns true for api-docs")
    void shouldNotFilter_forApiDocs() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api-docs/v3");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("shouldNotFilter returns false for transaction API paths")
    void shouldFilter_forTransactionEndpoints() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/transactions");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
