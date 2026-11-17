package com.aia.sparkfeedback.model;

import java.util.List;
import java.util.Map;

/** What the API sends to the browser. */
public final class Views {
    private Views() {}

    public record QuestionView(String key, String type, String text, int number) {}

    public record AnswerView(String questionKey, String question, String answer, String decision, String source) {}

    /**
     * One turn's result. {@code question} is what to ask next (null when finished); {@code repeat} means the same question is being asked
     * again because the answer was unclear; {@code heard}/{@code decision} are what Spark understood from the last answer; {@code phrases}
     * are Spark's fixed lines in the session language (greeting, clarify, noSpeech, thanks). Once finished, {@code answers} lists everything.
     */
    public record Turn(String sessionId, String status, String language, String customerName, String channel,
                       QuestionView question, boolean repeat, boolean noSpeech, String heard, String decision,
                       Map<String, String> phrases, List<AnswerView> answers) {}

    public record LoginResult(String token, Turn turn) {}
}
