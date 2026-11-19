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

    /**
     * decision: YES, NO, UNCLEAR, or INTERRUPT (the customer asked something instead of answering) or NOISE (not speech meant for Spark).
     * For INTERRUPT, {@code intent} is REPEAT, EXPLAIN or OFFTOPIC and {@code reply} is what Spark says before asking the question again.
     */
    public record Decision(String decision, double confidence, String reason, boolean viaModel, String reply, String intent) {
        public Decision(String decision, double confidence, String reason, boolean viaModel) { this(decision, confidence, reason, viaModel, null, null); }
    }

    /** Spark's reply to an interruption is limited to this many characters, whatever the model wrote. */
    private static final int MAX_REPLY = 320;

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
            - The answer must respond to what THIS question is about. A statement about something else is UNCLEAR even when it sounds positive or negative.
              Example: question "Do you feel the plan and premium suit your needs?", answer "yes I would recommend it to my friends" -> relevant=false, UNCLEAR.
              Example: question "Did you receive your documents on time?", answer "the agent was very nice" -> relevant=false, UNCLEAR.
              A bare "yes" or "no" (or "can lah", "cannot") is relevant to any yes/no question.
            - Some questions are short fragments that continue the previous topic, for example "Financial situation?" or "Priorities and objectives?".
              Read them as "Was/Did/Is <fragment> discussed or covered?". An answer that says yes or no, even while repeating the topic
              ("Yes, my financial situation was discussed"), is relevant and is YES or NO.
            - Decide the customer's INTENT first: ANSWER (they are answering), REPEAT (they ask you to repeat, say again or slow down), EXPLAIN (they do not
              understand the question or ask what it means), OFFTOPIC (they ask or say something else: their policy, a claim, the premium, the agent, small talk),
              NOISE (background speech, a TV, another person, or fragments clearly not meant for you).
            - For REPEAT, EXPLAIN and OFFTOPIC write "reply" in the customer's language: at most two short sentences, warm and professional, like a customer
              service consultant. REPEAT: say you are happy to repeat it. EXPLAIN: say in plain words what this question is asking, using ONLY the words of the
              question, adding no facts. OFFTOPIC: politely say you can only help with this feedback call, that for anything else about their policy they can call
              1800 248, and that you will carry on. Never give policy, product, medical, legal, tax or financial advice. Never answer the question for the customer.
              Do not include the question itself in the reply: it is asked again right after.
            - For ANSWER and NOISE leave "reply" empty. NOISE means decision UNCLEAR.
            - Answer UNCLEAR when the answer does not address the question, is off topic, is a question back, is genuinely mixed ("some yes some no"), or is gibberish.
            - Never invent an answer the customer did not give.
            Reply with JSON only: {"intent":"ANSWER"|"REPEAT"|"EXPLAIN"|"OFFTOPIC"|"NOISE","decision":"YES"|"NO"|"UNCLEAR","confidence":0.0-1.0,"relevant":true|false,"reply":"","reason":"<=12 words"}""";

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

        Decision quick = quickYesNo(cleaned);
        if (quick != null) return quick;

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
            String intent = n.path("intent").asText("ANSWER").toUpperCase(Locale.ROOT);
            String reply = n.path("reply").asText("").strip();
            if (reply.length() > MAX_REPLY) reply = reply.substring(0, MAX_REPLY).replaceAll("\\s+\\S*$", "") + "…";
            if (intent.equals("NOISE")) return new Decision("NOISE", 1.0, n.path("reason").asText(""), true, null, intent);
            if ((intent.equals("REPEAT") || intent.equals("EXPLAIN") || intent.equals("OFFTOPIC")) && !reply.isBlank())
                return new Decision("INTERRUPT", 1.0, n.path("reason").asText(""), true, reply, intent);
            String d = n.path("decision").asText("UNCLEAR").toUpperCase(Locale.ROOT);
            double c = Math.max(0, Math.min(1, n.path("confidence").asDouble(0)));
            if (!d.equals("YES") && !d.equals("NO")) d = "UNCLEAR";
            if (c < minConfidence || !n.path("relevant").asBoolean(true)) d = "UNCLEAR";
            return new Decision(d, c, n.path("reason").asText(""), true);
        } catch (Exception e) {
            log.warn("Decision model failed, treating the answer as unclear: {}", e.toString());
            return new Decision("UNCLEAR", 0, "model unavailable", true);
        }
    }

    private static final String CHOICE_SYSTEM = """
            You decide which option a customer picked in answer to a multiple-choice feedback question.
            The customer bought an insurance policy in Singapore and answers by speech (transcribed) or text, in English, Bahasa Melayu, Mandarin or Tamil,
            often mixed with Singlish. They may say the option number ("one", "number 2", "option 1"), or say the meaning in their own words.

            Rules:
            - Pick the option whose meaning the customer's answer clearly matches. Do not pick by guessing.
            - Judge the meaning: "I don't know any of them" matches an option like "Don't know the agent names at all"; "I know my agent" matches an option like "Know the agent".
            - If the answer fits none of the options, fits more than one, is off topic, is a question back, or is gibberish, answer UNCLEAR.
            - Never invent an answer the customer did not give.
            Reply with JSON only: {"choice":"<option key>"|"UNCLEAR","confidence":0.0-1.0,"relevant":true|false,"reason":"<=12 words"}""";

    /** Which option (by key) the customer picked, or UNCLEAR. Spoken or typed option numbers skip the model. */
    public Decision decideChoice(String question, java.util.List<com.aia.sparkfeedback.model.Questionnaire.Option> options, String answer, String lang) {
        String cleaned = answer == null ? "" : answer.strip();
        if (cleaned.isEmpty()) return new Decision("UNCLEAR", 0, "empty", false);
        String word = cleaned.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s。，！？、]+", "");
        for (var o : options) {
            if (word.equals(o.key().toLowerCase(Locale.ROOT)) || word.equals("option" + o.key().toLowerCase(Locale.ROOT)) || word.equals("number" + o.key().toLowerCase(Locale.ROOT)))
                return new Decision(o.key(), 1.0, "option number", false);
        }
        StringBuilder sb = new StringBuilder("Language: " + Phrases.languageName(lang) + "\nQuestion: " + question + "\nOptions:\n");
        for (var o : options) sb.append(o.key()).append(") ").append(o.text("en")).append(" | ").append(o.text(lang)).append("\n");
        sb.append("Customer's answer: ").append(cleaned);
        String user = sb.toString();
        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> callWith(CHOICE_SYSTEM, user), pool);
            String json;
            try {
                json = first.get(4, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException slow) {
                CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> callWith(CHOICE_SYSTEM, user), pool);
                json = (String) CompletableFuture.anyOf(first, second).get(25, TimeUnit.SECONDS);
            }
            JsonNode n = mapper.readTree(json);
            String d = n.path("choice").asText("UNCLEAR").trim();
            double c = Math.max(0, Math.min(1, n.path("confidence").asDouble(0)));
            final String picked = d;
            boolean known = options.stream().anyMatch(o -> o.key().equals(picked));
            if (!known || c < minConfidence || !n.path("relevant").asBoolean(true)) d = "UNCLEAR";
            return new Decision(d, c, n.path("reason").asText(""), true);
        } catch (Exception e) {
            log.warn("Choice decision failed, treating the answer as unclear: {}", e.toString());
            return new Decision("UNCLEAR", 0, "model unavailable", true);
        }
    }

    /** Which of the four languages the customer asked for (en, ms, zh, ta) or UNCLEAR. */
    public Decision decideLanguage(String answer) {
        String a = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) return new Decision("UNCLEAR", 0, "empty", false);
        Set<String> hits = new java.util.LinkedHashSet<>();
        if (a.contains("mandarin") || a.contains("chinese") || a.contains("华") || a.contains("中文") || a.contains("普通话") || a.contains("华语")) hits.add("zh");
        if (a.contains("malay") || a.contains("melayu") || a.contains("bahasa") || a.contains("马来")) hits.add("ms");
        if (a.contains("tamil") || a.contains("தமிழ்") || a.contains("淡米尔") || a.contains("泰米尔")) hits.add("ta");
        if (a.contains("english") || a.contains("inggeris") || a.contains("英文") || a.contains("英语")) hits.add("en");
        if (hits.size() == 1) return new Decision(hits.iterator().next(), 1.0, "language named", false);
        String user = "Customer's answer: " + answer.strip();
        try {
            String json = CompletableFuture.supplyAsync(() -> callWith(LANGUAGE_SYSTEM, user), pool).get(25, TimeUnit.SECONDS);
            JsonNode n = mapper.readTree(json);
            String d = n.path("language").asText("UNCLEAR").trim().toLowerCase(Locale.ROOT);
            double c = Math.max(0, Math.min(1, n.path("confidence").asDouble(0)));
            if (!Set.of("en", "ms", "zh", "ta").contains(d) || c < minConfidence) return new Decision("UNCLEAR", c, n.path("reason").asText(""), true);
            return new Decision(d, c, n.path("reason").asText(""), true);
        } catch (Exception e) {
            log.warn("Language decision failed, treating the answer as unclear: {}", e.toString());
            return new Decision("UNCLEAR", 0, "model unavailable", true);
        }
    }

    private static final String OPEN_SYSTEM = """
            A customer of an insurance company in Singapore is answering an OPEN feedback question by voice (transcribed), in English, Bahasa Melayu, Mandarin or Tamil,
            often mixed with Singlish. The microphone may also have picked up a television, radio or another person in the room.
            Decide the INTENT of the transcript:
            - ANSWER: a plausible reply to the question, even if short, informal, negative, or "nothing" / "no comments" / "nope all good".
            - NOISE: speech that is clearly NOT the customer replying to Spark: news or TV/radio programme text, an advertisement, a conversation between other people,
              or text about something unrelated to the question and to their policy or agent. A customer's own unrelated chat is OFFTOPIC, not NOISE.
            - REPEAT: they ask you to repeat or say it again.  EXPLAIN: they do not understand the question or ask what it means.
            - OFFTOPIC: they ask or say something else directed at you (their policy, a claim, the premium, small talk).
            For REPEAT, EXPLAIN and OFFTOPIC write "reply" in the customer's language: at most two short sentences, warm and professional. REPEAT: happy to repeat it.
            EXPLAIN: say in plain words what the question asks, using ONLY the words of the question, adding no facts. OFFTOPIC: politely say you can only help with this
            feedback call, that for anything else about their policy they can call 1800 248, and that you will carry on. Never give policy, product, medical, legal, tax or
            financial advice. Do not include the question itself in the reply. For ANSWER and NOISE leave "reply" empty.
            Reply with JSON only: {"intent":"ANSWER"|"NOISE"|"REPEAT"|"EXPLAIN"|"OFFTOPIC","reply":"","reason":"<=12 words"}""";

    /** For a spoken answer to an open question: ANSWER (decision TEXT), NOISE, or INTERRUPT with a reply. On any failure the words are kept as the answer. */
    public Decision decideOpen(String question, String answer, String lang) {
        String cleaned = answer == null ? "" : answer.strip();
        if (cleaned.isEmpty()) return new Decision("NOISE", 1.0, "empty", false, null, "NOISE");
        String user = "Language: " + Phrases.languageName(lang) + "\nQuestion: " + question + "\nTranscript: " + cleaned;
        try {
            String json = CompletableFuture.supplyAsync(() -> callWith(OPEN_SYSTEM, user), pool).get(20, TimeUnit.SECONDS);
            JsonNode n = mapper.readTree(json);
            String intent = n.path("intent").asText("ANSWER").toUpperCase(Locale.ROOT);
            String reply = n.path("reply").asText("").strip();
            if (reply.length() > MAX_REPLY) reply = reply.substring(0, MAX_REPLY).replaceAll("\\s+\\S*$", "") + "…";
            if (intent.equals("NOISE")) return new Decision("NOISE", 1.0, n.path("reason").asText(""), true, null, intent);
            if ((intent.equals("REPEAT") || intent.equals("EXPLAIN") || intent.equals("OFFTOPIC")) && !reply.isBlank())
                return new Decision("INTERRUPT", 1.0, n.path("reason").asText(""), true, reply, intent);
            return new Decision("TEXT", 1.0, "open answer", true);
        } catch (Exception e) {
            log.warn("Open-answer check failed, keeping the words as the answer: {}", e.toString());
            return new Decision("TEXT", 1.0, "check unavailable", true);
        }
    }

    private static final String LANGUAGE_SYSTEM = """
            A customer was asked which language they prefer for a phone call: Mandarin, Bahasa Malay or Tamil (English is also possible).
            They may answer in any of these languages, mixed with Singlish, spoken or typed (for example "华语", "bahasa lah", "தமிழ்", "Chinese can").
            Decide which language they chose. Answer UNCLEAR if no single language is chosen or the answer is off topic.
            Reply with JSON only: {"language":"en"|"ms"|"zh"|"ta"|"UNCLEAR","confidence":0.0-1.0,"reason":"<=12 words"}""";

    private static final Set<String> QUICK_YES = Set.of("yes", "yeah", "yep", "yup", "ya", "sure", "ok", "okay", "correct", "definitely", "absolutely", "certainly", "right");
    private static final Set<String> QUICK_NO = Set.of("no", "nope", "nah", "never");
    private static final Set<String> NEGATIONS = Set.of("not", "no", "nope", "never", "cannot", "dont", "didnt", "doesnt", "wasnt", "werent", "havent", "hadnt", "isnt", "cant", "wont", "nothing", "nobody", "none");
    /** Words that mean the customer is asking or doubting rather than answering: those always go to the model. */
    private static final Set<String> CUES = Set.of("what", "why", "how", "who", "when", "where", "which", "repeat", "again", "sorry", "pardon", "mean", "explain",
            "understand", "confused", "unsure", "maybe", "perhaps", "but", "however", "actually", "wait", "hold", "can", "could", "would", "should", "please", "claim", "premium");

    /**
     * Short, plain English answers such as "yes, it was explained" or "no, nobody offered me a gift" are decided here, without waiting about 1.5 s for the model.
     * Anything longer, hedged, negated after a yes, or containing a question or doubt word goes to the model as before.
     */
    private static Decision quickYesNo(String text) {
        if (text.contains("?") || text.length() > 60) return null;
        String[] w = text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}]", " ").trim().split("\\s+");
        if (w.length == 0 || w.length > 7) return null;
        for (String t : w) if (!t.matches("[a-z']+") || CUES.contains(t.replace("'", ""))) return null;
        String first = w[0];
        if (QUICK_YES.contains(first)) {
            for (int i = 1; i < w.length; i++) if (NEGATIONS.contains(w[i].replace("'", ""))) return null;
            return new Decision("YES", 0.95, "plain yes", false);
        }
        if (QUICK_NO.contains(first)) {
            for (int i = 1; i < w.length; i++) if (QUICK_YES.contains(w[i])) return null;
            return new Decision("NO", 0.95, "plain no", false);
        }
        return null;
    }

    private String call(String user) { return callWith(SYSTEM, user); }

    private String callWith(String system, String user) {
        var r = chat.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user)),
                AzureOpenAiChatOptions.builder().deploymentName(deployment).responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.0).maxTokens(220).build()));
        return r.getResult().getOutput().getText();
    }
}
