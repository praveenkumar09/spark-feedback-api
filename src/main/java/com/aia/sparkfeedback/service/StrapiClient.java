package com.aia.sparkfeedback.service;

import com.aia.sparkfeedback.model.Questionnaire;
import com.aia.sparkfeedback.model.Questionnaire.Branch;
import com.aia.sparkfeedback.model.Questionnaire.Question;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the published questionnaire for a channel from Strapi (the configuration tool). Called once per session, at its start. */
@Component
public class StrapiClient {

    private final RestClient http;
    private final String baseUrl;

    public StrapiClient(@Value("${spark.strapi.url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        org.springframework.http.client.SimpleClientHttpRequestFactory f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(15000);
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** The most recently published questionnaire for the channel, or null if none is published. */
    public Questionnaire fetch(String channel) {
        java.net.URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/api/questionnaires")
                .queryParam("filters[channel][$eq]", channel)
                .queryParam("populate[questions][populate][0]", "texts")
                .queryParam("populate[questions][populate][1]", "branches")
                .queryParam("sort[0]", "publishedAt:desc")          // if a channel has several published, the most recently published one is used
                .queryParam("pagination[pageSize]", 1)
                .build().encode().toUri();
        JsonNode root = http.get().uri(uri).retrieve().body(JsonNode.class);
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) return null;
        JsonNode qn = data.get(0);
        List<Question> questions = new ArrayList<>();
        for (JsonNode q : qn.path("questions")) {
            Map<String, String> texts = new LinkedHashMap<>();
            for (JsonNode t : q.path("texts")) texts.put(t.path("locale").asText(), t.path("text").asText());
            List<Branch> branches = new ArrayList<>();
            for (JsonNode b : q.path("branches")) {
                String next = b.path("next").asText("").trim();
                branches.add(new Branch(b.path("when").asText("ANY"), next.isEmpty() ? null : next));
            }
            List<Questionnaire.Option> options = new ArrayList<>();
            for (JsonNode o : q.path("options")) {
                Map<String, String> ot = new LinkedHashMap<>();
                for (JsonNode t : o.path("texts")) ot.put(t.path("locale").asText(), t.path("text").asText());
                options.add(new Questionnaire.Option(o.path("key").asText(), ot));
            }
            questions.add(new Question(q.path("key").asText(), q.path("order").asInt(1), q.path("type").asText("YES_NO"), texts, branches, options));
        }
        questions.sort(Comparator.comparingInt(Question::order));
        if (questions.isEmpty()) return null;
        return new Questionnaire(qn.path("key").asText(), qn.path("channel").asText(channel), qn.path("title").asText(), questions);
    }
}
