package com.videoagent.middleware.jwt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private static final String STRONG = "va-local-strong-secret-0123456789abcdef"; // 40 字节

    @Test
    void nullSecret_rejected() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil(null, 72));
    }

    @Test
    void blankSecret_rejected() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("   ", 72));
    }

    @Test
    void shortSecret_rejected() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("too-short", 72));
    }

    @Test
    void strongSecret_signAndVerify_roundtrip() {
        JwtUtil jwt = new JwtUtil(STRONG, 72);
        String token = jwt.create(7L, "tester");
        assertEquals(7L, jwt.verifyUserId(token));
    }

    @Test
    void tamperedToken_returnsNull() {
        JwtUtil jwt = new JwtUtil(STRONG, 72);
        String token = jwt.create(7L, "tester");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertNull(jwt.verifyUserId(tampered));
    }

    @Test
    void tokenFromOtherKey_returnsNull() {
        JwtUtil signer = new JwtUtil(STRONG, 72);
        JwtUtil other = new JwtUtil("another-strong-secret-key-000011112222", 72);
        String token = signer.create(7L, "tester");
        assertNull(other.verifyUserId(token));
    }
}