package com.aia.sparkfeedback.web;

import com.aia.sparkfeedback.auth.AdminAuth;
import com.aia.sparkfeedback.service.AdminStore;
import com.aia.sparkfeedback.service.AdminStore.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin panel API: sign in, then read every response. Read-only. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record LoginRequest(String password) {}

    private final AdminAuth auth;
    private final AdminStore store;

    public AdminController(AdminAuth auth, AdminStore store) {
        this.auth = auth;
        this.store = store;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest r) {
        if (!auth.enabled()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "The admin panel is not switched on."));
        if (auth.locked()) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Too many attempts. Please wait a few minutes."));
        return auth.login(r.password())
                .<ResponseEntity<Map<String, String>>>map(t -> ResponseEntity.ok(Map.of("token", t)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "That password is not correct.")));
    }

    @GetMapping("/customers")
    public List<AdminStore.Customer> customers() { return store.customers(); }

    @GetMapping("/responses")
    public List<Response> responses() { return store.responses(1000); }
}
