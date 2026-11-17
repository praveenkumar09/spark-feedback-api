package com.aia.sparkfeedback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Turns what the customer said (a transcript or typed text) into YES, NO or UNCLEAR for the question that was asked, using the best chat model
 * deployed (spark.decision.deployment). Only unmistakable one-word answers skip the model. A weak or failed decision is UNCLEAR, never a guess.
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    public record Decision(String decision, double confidence, String reason, boolean viaModel) {}

    private static final Set<String> YES = Set.of("yes", "yeah", "yep", "yup", "y", "sure", "ya", "betul", "ok", "okay",
            "是", "是的", "对", "对的", "有", "好", "好的", "ஆம்", "ஆமாம்", "ஆமா");
    private static final Set<String> NO = Set.of("no", "nope", "nah", "n", "tak", "tidak", "bukan", "belum", "tiada",
            "不", "不是", "没有", "沒有", "不对", "不行", "இல்லை", "இல்ல", "வேண்டாம்");

    private static final String SYSTEM = """
            You decide whether a customer's answer to a yes/no feedback question means YES or NO.
            The customer bought an insurance policy in Singapore and is answering by speech (transcribed) or text, in English, Bahasa Melayu, Mandarin or Tamil,
            often mixed with Singlish ("can lah" = yes, "cannot" = no, "ok lah" = yes, "not really" = no).

            Rules:
            - Judge the answer against the exact question. For "Did you feel pressured?", "no, I was fine" is NO; "yes, a bit" is YES.
            - A hedged answer that still leans one way ("I think so", "not really") counts, with lower confidence.
            - Answer UNCLEAR when the answer does not address the question, is off topic, is a question back, is genuinely mixed ("some yes some no"), or is gibberish.
            - Never invent an answer the customer did not give.
            Reply with JSON only: {"decision":"YES"|"NO"|"UNCLEAR","confidence":0.0-1.0,"reason":"<=12 words"}""";

    private final ChatModel chat;
    private final String deployment;
    private final double minConfidence;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "decision-llm");
        t.setDaemon(true);
        return t;
    });

    public DecisionService(ChatModel chat, @Value("${spark.decision.deployment}") String deployment,
                           @Value("${spark.decision.min-confidence:0.65}") double minConfidence) {
        this.chat = chat;
        this.deployment = deployment;
        this.minConfidence = minConfidence;
    }

    public Decision decide(String question, String answer, String lang) {
        String cleaned = answer == null ? "" : answer.strip();
        if (cleaned.isEmpty()) return new Decision("UNCLEAR", 0, "empty", false);
        String word = cleaned.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s。，！？、]+", "");
        if (YES.contains(word)) return new Decision("YES", 1.0, "plain yes", false);
        if (NO.contains(word)) return new Decision("NO", 1.0, "plain no", false);

        String user = "Language: " + Phrases.languageName(lang) + "\nQuestion: " + question + "\nCustomer's answer: " + cleaned;
        try {
            // Azure now and then leaves a call hanging: a second identical attempt starts after 4 s and the first answer wins.
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> call(user), pool);
            String json;
            try {
                json = first.get(4, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException slow) {
                CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> call(user), pool);
                json = (String) CompletableFuture.anyOf(first, second).get(25, TimeUnit.SECONDS);
            }
            JsonNode n = mapper.readTree(json);
            String d = n.path("decision").asText("UNCLEAR").toUpperCase(Locale.ROOT);
            double c = Math.max(0, Math.min(1, n.path("confidence").asDouble(0)));
            if (!d.equals("YES") && !d.equals("NO")) d = "UNCLEAR";
            if (c < minConfidence) d = "UNCLEAR";
            return new Decision(d, c, n.path("reason").asText(""), true);
        } catch (Exception e) {
            log.warn("Decision model failed, treating the answer as unclear: {}", e.toString());
            return new Decision("UNCLEAR", 0, "model unavailable", true);
        }
    }

    private String call(String user) {
        var r = chat.call(new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(user)),
                AzureOpenAiChatOptions.builder().deploymentName(deployment).responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.0).maxTokens(80).build()));
        return r.getResult().getOutput().getText();
    }
}
