package com.aia.sparkfeedback.auth;

import com.aia.sparkfeedback.service.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * Every call after login carries the session's secret token ("Authorization: Bearer ..."). A token opens only its own session, so one
 * customer's browser can never read or write another's answers. Only a hash of the token is stored.
 */
@Component
public class SessionAuth implements HandlerInterceptor {

    public static final String ATTR_SESSION = "sparkSessionId";
    private final SessionStore store;

    public SessionAuth(SessionStore store) { this.store = store; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) return true;
        String h = req.getHeader("Authorization");
        String token = h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : req.getParameter("token");
        UUID sid = token == null || token.isBlank() ? null : store.sessionForToken(token).orElse(null);
        if (sid == null) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Please sign in again.\"}");
            return false;
        }
        // A path that names a session may only name the caller's own.
        String path = req.getRequestURI();
        String prefix = "/api/sessions/";
        if (path.startsWith(prefix)) {
            String named = path.substring(prefix.length()).split("/")[0];
            if (!named.equals(sid.toString())) {
                res.setStatus(403);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Not your session.\"}");
                return false;
            }
        }
        req.setAttribute(ATTR_SESSION, sid);
        return true;
    }

    public static Map<String, String> error(String m) { return Map.of("error", m); }
}
