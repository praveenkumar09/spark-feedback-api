package com.aia.sparkfeedback.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** A questionnaire as read from Strapi. A copy is stored on every session at its start, so editing the questions never changes a session in progress. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Questionnaire(String key, String channel, String title, List<Question> questions) implements java.io.Serializable {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(String key, int order, String type, Map<String, String> texts, List<Branch> branches) implements java.io.Serializable {
        /** The wording in the language, falling back to English. */
        public String text(String lang) {
            String t = texts == null ? null : texts.get(lang);
            if (t == null || t.isBlank()) t = texts == null ? null : texts.get("en");
            return t == null ? key : t;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Branch(String when, String next) implements java.io.Serializable {}

    public Question find(String questionKey) {
        return questions.stream().filter(q -> q.key().equals(questionKey)).findFirst().orElse(null);
    }

    public Question first() {
        return questions.stream().min(java.util.Comparator.comparingInt(Question::order)).orElse(null);
    }
}
