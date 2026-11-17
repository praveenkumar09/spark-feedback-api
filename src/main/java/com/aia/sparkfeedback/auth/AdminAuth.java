package com.aia.sparkfeedback.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin sign-in: one password from SPARK_ADMIN_PASSWORD. A correct password returns a random token (kept in memory, hashed, 8 hours); every
 * /api/admin call except login must carry it. With no password configured the admin panel is switched off. Wrong guesses lock the panel briefly.
 */
@Component
public class AdminAuth implements HandlerInterceptor {

    private final String password;
    private final ConcurrentHashMap<String, Instant> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private int failures;
    private Instant lockedUntil = Instant.EPOCH;

    public AdminAuth(@Value("${spark.admin.password:}") String password) { this.password = password == null ? "" : password; }

    public boolean enabled() { return !password.isBlank(); }

    private static String hash(String v) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** A token for the right password, empty for a wrong one or while locked. */
    public synchronized Optional<String> login(String given) {
        if (!enabled() || Instant.now().isBefore(lockedUntil) || given == null) return Optional.empty();
        if (!MessageDigest.isEqual(hash(given).getBytes(StandardCharsets.UTF_8), hash(password).getBytes(StandardCharsets.UTF_8))) {
            if (++failures >= 5) { failures = 0; lockedUntil = Instant.now().plus(Duration.ofMinutes(5)); }
            return Optional.empty();
        }
        failures = 0;
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokens.values().removeIf(t -> t.isBefore(Instant.now()));
        tokens.put(hash(token), Instant.now().plus(Duration.ofHours(8)));
        return Optional.of(token);
    }

    public synchronized boolean locked() { return Instant.now().isBefore(lockedUntil); }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod()) || req.getRequestURI().equals("/api/admin/login")) return true;
        String h = req.getHeader("Authorization");
        String token = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : "";
        Instant exp = token.isEmpty() ? null : tokens.get(hash(token));
        if (exp == null || exp.isBefore(Instant.now())) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Please sign in.\"}");
            return false;
        }
        return true;
    }
}
