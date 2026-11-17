package com.aia.sparkfeedback.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** After a few wrong date-of-birth guesses for one policy number, that policy is locked for a few minutes. */
@Component
public class LoginThrottle {

    private record Entry(int failures, Instant lockedUntil) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lock;

    public LoginThrottle(@Value("${spark.login.max-failures:5}") int maxFailures, @Value("${spark.login.lock-minutes:5}") int minutes) {
        this.maxFailures = maxFailures;
        this.lock = Duration.ofMinutes(minutes);
    }

    public boolean locked(String policyNo) {
        Entry e = entries.get(policyNo);
        return e != null && e.lockedUntil() != null && e.lockedUntil().isAfter(Instant.now());
    }

    public void failed(String policyNo) {
        entries.merge(policyNo, new Entry(1, null), (old, one) -> {
            int n = (old.lockedUntil() != null && old.lockedUntil().isBefore(Instant.now()) ? 0 : old.failures()) + 1;
            return new Entry(n, n >= maxFailures ? Instant.now().plus(lock) : null);
        });
    }

    public void succeeded(String policyNo) { entries.remove(policyNo); }
}
