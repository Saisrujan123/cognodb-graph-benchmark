package io.cognodb.benchmark.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {
    @Test
    void removesUrisAndPasswordValues() {
        String redacted = SecretRedactor.redact(
                "failed at bolt+s://private.example.test:7687 password=very-secret"
        );

        assertThat(redacted)
                .doesNotContain("private.example.test")
                .doesNotContain("very-secret")
                .contains("[REDACTED_URI]")
                .contains("[REDACTED]");
    }

    @Test
    void removesBareHostsFromDriverErrors() {
        assertThat(SecretRedactor.redact("unable to connect to private.example.test:7687"))
                .doesNotContain("private.example.test")
                .contains("[REDACTED_HOST]");
    }

    @Test
    void removesCommonTokenAndApiKeyLabels() {
        assertThat(SecretRedactor.redact("token=abc123 api-key: key456"))
                .doesNotContain("abc123")
                .doesNotContain("key456");
    }

    @Test
    void removesUserIdentityAndIpv6Hosts() {
        String redacted = SecretRedactor.redact(
                "username=private-user instance-id: account-123 at [2001:db8::1]:7687 or 2001:db8::2");

        assertThat(redacted)
                .doesNotContain("private-user")
                .doesNotContain("account-123")
                .doesNotContain("2001:db8")
                .contains("[REDACTED_HOST]");
    }

    @Test
    void removesBareIpv4Hosts() {
        assertThat(SecretRedactor.redact("unable to connect to 10.20.30.40"))
                .doesNotContain("10.20.30.40")
                .contains("[REDACTED_HOST]");
    }

    @Test
    void removesPrivateDatabaseGraphAndTenantLabels() {
        String redacted = SecretRedactor.redact(
                "database name: private-db graph=tenant_graph tenant: acct-7 workspace=internal");

        assertThat(redacted)
                .doesNotContain("private-db")
                .doesNotContain("tenant_graph")
                .doesNotContain("acct-7")
                .doesNotContain("internal")
                .contains("[REDACTED]");
    }

}
