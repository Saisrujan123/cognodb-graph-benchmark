package io.cognodb.benchmark.util;

import java.util.regex.Pattern;

public final class SecretRedactor {
    private static final Pattern URI_PATTERN = Pattern.compile(
            "(?i)(bolt(?:\\+s|\\+ssc)?|neo4j(?:\\+s|\\+ssc)?|redis(?:s)?|https?)://[^\\s,;]+"
    );
    private static final Pattern SENSITIVE_VALUE_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|authorization|token|secret|api[_-]?key|username|user|"
                    + "instance[_ -]?id|database(?:[_ -]?name)?|graph|tenant|workspace)"
                    + "(\\s*[:=]\\s*)[^\\s,;]+"
    );
    private static final Pattern BRACKETED_IPV6_PATTERN = Pattern.compile(
            "(?i)\\[[0-9a-f:]+\\](?::\\d{1,5})?"
    );
    private static final Pattern BARE_IPV6_PATTERN = Pattern.compile(
            "(?i)(?<![0-9a-f])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f])"
    );
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d{1,5})?\\b|"
                    + "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b"
    );

    private SecretRedactor() {
    }

    public static String redact(String message) {
        if (message == null) {
            return "";
        }
        String withoutUris = URI_PATTERN.matcher(message).replaceAll("[REDACTED_URI]");
        String withoutSensitiveValues = SENSITIVE_VALUE_PATTERN.matcher(withoutUris)
                .replaceAll("$1$2[REDACTED]");
        String withoutBracketedIpv6 = BRACKETED_IPV6_PATTERN.matcher(withoutSensitiveValues)
                .replaceAll("[REDACTED_HOST]");
        String withoutBareIpv6 = BARE_IPV6_PATTERN.matcher(withoutBracketedIpv6)
                .replaceAll("[REDACTED_HOST]");
        return HOST_PATTERN.matcher(withoutBareIpv6).replaceAll("[REDACTED_HOST]");
    }
}
