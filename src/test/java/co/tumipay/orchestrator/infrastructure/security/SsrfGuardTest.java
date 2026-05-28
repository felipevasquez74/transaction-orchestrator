package co.tumipay.orchestrator.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SsrfGuard Unit Tests")
class SsrfGuardTest {

    private SsrfGuard ssrfGuard;

    @BeforeEach
    void setUp() {
        ssrfGuard = new SsrfGuard();
    }

    @Test
    @DisplayName("Returns null (safe) for a valid public HTTPS URL")
    void returnsNull_forPublicHttpsUrl() {
        assertThat(ssrfGuard.validateSafety("https://api.example.com/webhook", "webhook_url")).isNull();
    }

    @Test
    @DisplayName("Returns null (safe) for a valid public HTTP URL")
    void returnsNull_forPublicHttpUrl() {
        assertThat(ssrfGuard.validateSafety("http://api.example.com/webhook", "webhook_url")).isNull();
    }

    @Test
    @DisplayName("Returns null for null URL (format validation already handled upstream)")
    void returnsNull_forNullUrl() {
        assertThat(ssrfGuard.validateSafety(null, "webhook_url")).isNull();
    }

    @Test
    @DisplayName("Returns null for blank URL")
    void returnsNull_forBlankUrl() {
        assertThat(ssrfGuard.validateSafety("   ", "webhook_url")).isNull();
    }

    @Test
    @DisplayName("Returns null for malformed URL (format validation already handled upstream)")
    void returnsNull_forMalformedUrl() {
        assertThat(ssrfGuard.validateSafety("not-a-url", "webhook_url")).isNull();
    }

    @Test
    @DisplayName("Blocks localhost by hostname")
    void blocks_localhost() {
        String result = ssrfGuard.validateSafety("https://localhost/callback", "webhook_url");
        assertThat(result).isNotNull().contains("restricted host").contains("webhook_url");
    }

    @Test
    @DisplayName("Blocks ip6-localhost")
    void blocks_ip6Localhost() {
        String result = ssrfGuard.validateSafety("http://ip6-localhost/callback", "redirect_url");
        assertThat(result).isNotNull().contains("restricted host");
    }

    @Test
    @DisplayName("Blocks loopback 127.x.x.x addresses")
    void blocks_loopbackIp() {
        assertThat(ssrfGuard.validateSafety("http://127.0.0.1/callback", "webhook_url"))
                .isNotNull().contains("private or reserved");
        assertThat(ssrfGuard.validateSafety("http://127.1.2.3/hook", "webhook_url"))
                .isNotNull().contains("private or reserved");
    }

    @Test
    @DisplayName("Blocks 10.x.x.x private network")
    void blocks_privateNetwork_10x() {
        assertThat(ssrfGuard.validateSafety("http://10.0.0.1/hook", "webhook_url"))
                .isNotNull().contains("private or reserved");
        assertThat(ssrfGuard.validateSafety("http://10.255.255.255/hook", "webhook_url"))
                .isNotNull().contains("private or reserved");
    }

    @Test
    @DisplayName("Blocks 192.168.x.x private network")
    void blocks_privateNetwork_192168() {
        assertThat(ssrfGuard.validateSafety("http://192.168.1.1/hook", "webhook_url"))
                .isNotNull().contains("private or reserved");
    }

    @Test
    @DisplayName("Blocks 172.16-31.x.x private network")
    void blocks_privateNetwork_172() {
        assertThat(ssrfGuard.validateSafety("http://172.16.0.1/hook", "webhook_url"))
                .isNotNull().contains("private or reserved");
        assertThat(ssrfGuard.validateSafety("http://172.31.255.255/hook", "webhook_url"))
                .isNotNull().contains("private or reserved");
    }

    @Test
    @DisplayName("Blocks non-http/https schemes")
    void blocks_fileScheme() {
        String result = ssrfGuard.validateSafety("file:///etc/passwd", "webhook_url");
        assertThat(result).isNotNull().contains("disallowed scheme").contains("file");
    }

    @Test
    @DisplayName("Blocks link-local 169.254.x.x addresses")
    void blocks_linkLocal() {
        assertThat(ssrfGuard.validateSafety("http://169.254.169.254/metadata", "webhook_url"))
                .isNotNull().contains("private or reserved");
    }
}
