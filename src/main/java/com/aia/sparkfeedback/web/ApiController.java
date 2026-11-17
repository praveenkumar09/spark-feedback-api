package com.aia.sparkfeedback.web;

import com.aia.sparkfeedback.auth.SessionAuth;
import com.aia.sparkfeedback.model.Views.LoginResult;
import com.aia.sparkfeedback.model.Views.Turn;
import com.aia.sparkfeedback.service.FeedbackService;
import com.aia.sparkfeedback.service.FeedbackService.LoginException;
import com.aia.sparkfeedback.service.SpeechService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    public record LoginRequest(String policyNo, String dob, String language) {}
    public record AnswerRequest(String questionKey, String text, String source, String choice) {}
    public record LanguageRequest(String language) {}
    public record SpeakRequest(String what) {}

    private final FeedbackService service;
    private final SpeechService speech;

    public ApiController(FeedbackService service, SpeechService speech) {
        this.service = service;
        this.speech = speech;
    }

    private static UUID sid(HttpServletRequest r) { return (UUID) r.getAttribute(SessionAuth.ATTR_SESSION); }

    @PostMapping("/auth/login")
    public LoginResult login(@RequestBody LoginRequest r) {
        return service.login(r.policyNo(), r.dob(), r.language());
    }

    @GetMapping("/sessions/{id}")
    public Turn get(HttpServletRequest req) { return service.current(sid(req)); }

    @PostMapping("/sessions/{id}/answer")
    public Turn answer(HttpServletRequest req, @RequestBody AnswerRequest a) {
        return service.answer(sid(req), a.questionKey(), a.text(), "BUTTON".equals(a.source()) ? "BUTTON" : "TEXT", a.choice());
    }

    @PostMapping(value = "/sessions/{id}/answer/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Turn answerAudio(HttpServletRequest req, @RequestParam("questionKey") String questionKey, @RequestParam("audio") MultipartFile audio) throws Exception {
        return service.answerAudio(sid(req), questionKey, audio.getBytes(), audio.getContentType());
    }

    @PutMapping("/sessions/{id}/language")
    public Turn language(HttpServletRequest req, @RequestBody LanguageRequest l) { return service.setLanguage(sid(req), l.language()); }

    /** Take back the latest answer and ask that question again. */
    @PostMapping("/sessions/{id}/undo")
    public Turn undo(HttpServletRequest req) { return service.undo(sid(req)); }

    @PostMapping("/sessions/{id}/restart")
    public Turn restart(HttpServletRequest req) { return service.restart(sid(req)); }

    /** Spark's voice for a fixed purpose (greeting, clarify, noSpeech, thanks, or the current question). 204 means: use the browser's own voice. */
    @PostMapping("/sessions/{id}/speak")
    public ResponseEntity<byte[]> speak(HttpServletRequest req, @RequestBody SpeakRequest r) {
        UUID id = sid(req);
        String text = service.sayText(id, r.what());
        if (text == null) return ResponseEntity.noContent().build();
        byte[] audio = speech.speak(text, service.language(id));
        if (audio == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).header("Cache-Control", "no-store").body(audio);
    }

    // ── errors: never leak internals ─────────────────────────────────────────────

    @ExceptionHandler(LoginException.class)
    public ResponseEntity<Map<String, String>> login(LoginException e) {
        String m = e.status == 429 ? "Too many attempts. Please wait a few minutes and try again." : "The policy number or date of birth is not correct.";
        return ResponseEntity.status(e.status).body(Map.of("error", m));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        log.error("Request failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Spark is having trouble right now. Please try again in a moment."));
    }
}
