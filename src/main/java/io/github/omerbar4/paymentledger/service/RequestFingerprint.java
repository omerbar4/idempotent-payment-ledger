package io.github.omerbar4.paymentledger.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SHA-256 fingerprint of the semantically relevant request fields. Stored alongside the
 * idempotency key so that reusing a key for a <em>different</em> request can be detected.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(Object... parts) {
        String canonical = Stream.of(parts)
                .map(p -> Objects.toString(p, ""))
                .map(p -> p.length() + ":" + p)
                .collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
