package com.aia.sparkfeedback.graph;

import com.aia.sparkfeedback.model.Questionnaire;
import com.aia.sparkfeedback.model.Questionnaire.Branch;
import com.aia.sparkfeedback.model.Questionnaire.Question;
import com.aia.sparkfeedback.service.CallScriptTags;
import com.aia.sparkfeedback.service.DecisionService;
import com.aia.sparkfeedback.service.DecisionService.Decision;
import com.aia.sparkfeedback.service.Phrases;
import com.aia.sparkfeedback.service.SessionStore;
import com.aia.sparkfeedback.service.SessionStore.Session;
import com.aia.sparkfeedback.service.StrapiClient;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The conversation as a LangGraph4j graph. One compiled graph is shared by every customer; each call to {@link #run} is one turn for one
 * session, with the session id as input and all durable state in Postgres, so concurrent sessions never touch each other.
 *
 * <pre>
 *   START ─ route ─┬─ (phase START)  decide_channel → load_questionnaire → pick_first ─────────────────────────────► END
 *                  └─ (phase ANSWER) interpret ─┬─ unclear, attempts left → reask ──────────────────────────────► END
 *                                               └─ otherwise → store_answer → resolve_next (branch rules) ───────► END
 * </pre>
 * The decision between questions is always made by the branch rules written in Strapi; the model only reads the customer's yes/no.
 */
@Component
public class FeedbackGraph {

    public static class State extends AgentState {
        public State(Map<String, Object> init) { super(init); }
    }

    private final SessionStore store;
    private final StrapiClient strapi;
    private final DecisionService decisions;
    private final CallScriptTags tags;
    private final int maxUnclear;
    private final CompiledGraph<State> graph;

    public FeedbackGraph(SessionStore store, StrapiClient strapi, DecisionService decisions, CallScriptTags tags,
                         @Value("${spark.decision.max-unclear-attempts:2}") int maxUnclear) throws Exception {
        this.store = store;
        this.strapi = strapi;
        this.decisions = decisions;
        this.tags = tags;
        this.maxUnclear = maxUnclear;
        this.graph = build();
    }

    /** One turn. Input: sessionId, phase (START|ANSWER) and, for ANSWER, answer, source, choice. Output: repeat, heard, decision. */
    public Map<String, Object> run(Map<String, Object> input) {
        return graph.invoke(input).map(AgentState::data).orElseGet(HashMap::new);
    }

    private CompiledGraph<State> build() throws Exception {
        StateGraph<State> g = new StateGraph<>(State::new);
        g.addNode("route", AsyncNodeAction.node_async(s -> Map.<String, Object>of()));
        g.addNode("decide_channel", AsyncNodeAction.node_async(this::decideChannel));
        g.addNode("load_questionnaire", AsyncNodeAction.node_async(this::loadQuestionnaire));
        g.addNode("pick_first", AsyncNodeAction.node_async(this::pickFirst));
        g.addNode("interpret", AsyncNodeAction.node_async(this::interpret));
        g.addNode("reask", AsyncNodeAction.node_async(this::reask));
        g.addNode("store_answer", AsyncNodeAction.node_async(this::storeAnswer));
        g.addNode("resolve_next", AsyncNodeAction.node_async(this::resolveNext));

        g.addEdge(StateGraph.START, "route");
        g.addConditionalEdges("route",
                AsyncEdgeAction.edge_async(s -> "ANSWER".equals(s.<String>value("phase").orElse("START")) ? "answer" : "start"),
                Map.of("start", "decide_channel", "answer", "interpret"));
        g.addEdge("decide_channel", "load_questionnaire");
        g.addEdge("load_questionnaire", "pick_first");
        g.addEdge("pick_first", StateGraph.END);
        g.addConditionalEdges("interpret",
                AsyncEdgeAction.edge_async(s -> s.<Boolean>value("reask").orElse(false) ? "reask" : "store"),
                Map.of("reask", "reask", "store", "store_answer"));
        g.addEdge("reask", StateGraph.END);
        g.addEdge("store_answer", "resolve_next");
        g.addEdge("resolve_next", StateGraph.END);
        return g.compile();
    }

    private static UUID id(State s) { return UUID.fromString(s.<String>value("sessionId").orElseThrow()); }

    // ── start of a session ───────────────────────────────────────────────────────

    /** NFTF and FTF have their own sets; any other way of buying (bancassurance, agency, ...) uses the general set. */
    private Map<String, Object> decideChannel(State s) {
        Session session = store.load(id(s));
        String ch = session.channel() == null ? "" : session.channel().toUpperCase();
        return Map.of("channel", ch.equals("NFTF") || ch.equals("FTF") ? ch : "GENERAL");
    }

    private Map<String, Object> loadQuestionnaire(State s) {
        String channel = s.<String>value("channel").orElse("GENERAL");
        Questionnaire q = strapi.fetch(channel);
        if (q == null && !channel.equals("GENERAL")) q = strapi.fetch("GENERAL");
        if (q == null) throw new IllegalStateException("No published questionnaire for " + channel);
        return Map.of("questionnaire", q);
    }

    private Map<String, Object> pickFirst(State s) {
        Questionnaire q = s.<Questionnaire>value("questionnaire").orElseThrow();
        Set<String> mine = tags.tagsFor(store.load(id(s)).policyNo());
        Question first = q.questions().stream().filter(x -> x.order() < CallScriptTags.FOLLOW_UP_ORDER && CallScriptTags.applies(x.key(), mine))
                .min(java.util.Comparator.comparingInt(Question::order)).orElse(null);
        if (first == null) throw new IllegalStateException("The questionnaire has no questions");
        store.saveQuestionnaire(id(s), q, first.key());
        return Map.of("firstQuestion", first.key());
    }

    // ── an answer ────────────────────────────────────────────────────────────────

    /** Reads the customer's answer as YES / NO (or keeps open text as it is) and decides whether to ask the question again. */
    private Map<String, Object> interpret(State s) {
        Session session = store.load(id(s));
        Question q = session.questionnaire().find(session.currentQuestion());
        String text = s.<String>value("answer").orElse("").strip();
        String source = s.<String>value("source").orElse("TEXT");
        String qText = q.text(session.language());
        Map<String, Object> out = new HashMap<>();
        out.put("questionText", qText);
        out.put("heard", text);
        Decision d;
        if ("OPEN_TEXT".equals(q.type()) && !CallScriptTags.LANGUAGE_QUESTION.equals(q.key()) && !"VOICE".equals(source)) {
            out.put("decision", "TEXT");                     // typed: it is the customer's own words
            out.put("confidence", 1.0);
            out.put("reask", false);
            return out;
        }
        boolean openVoice = "OPEN_TEXT".equals(q.type()) && !CallScriptTags.LANGUAGE_QUESTION.equals(q.key());
        String choice = s.<String>value("choice").orElse("");
        if (q.isChoice()) {
            boolean pickedByButton = "BUTTON".equals(source) && q.options().stream().anyMatch(o -> o.key().equals(choice));
            d = pickedByButton ? new Decision(choice, 1.0, "chosen with a button", false)
                    : decisions.decideChoice(qText, q.options(), text, session.language());
        } else if (openVoice) {
            d = decisions.decideOpen(qText, text, session.language());   // a spoken answer may be the television: check before saving it
        } else if (CallScriptTags.LANGUAGE_QUESTION.equals(q.key())) {
            d = decisions.decideLanguage(text);
        } else if ("BUTTON".equals(source) && (choice.equals("YES") || choice.equals("NO"))) d = new Decision(choice, 1.0, "chosen with a button", false);
        else d = decisions.decide(qText, text, session.language());
        boolean interruption = "INTERRUPT".equals(d.decision());
        boolean noise = "NOISE".equals(d.decision());
        // Asking to repeat or explain never uses up an attempt; off-topic talk and noise do, so a conversation that wanders eventually moves on.
        boolean uncounted = interruption && !"OFFTOPIC".equals(d.intent());
        boolean reask = (uncounted && interruption) || ((interruption || noise || "UNCLEAR".equals(d.decision())) && session.unclearAttempts() < maxUnclear);
        out.put("decision", reask || !(interruption || noise) ? d.decision() : "UNCLEAR");
        out.put("confidence", d.confidence());
        out.put("reask", reask);
        out.put("uncounted", uncounted);
        if (reask && interruption) out.put("reply", d.reply());
        return out;
    }

    private Map<String, Object> reask(State s) {
        Session session = store.load(id(s));
        if (!s.<Boolean>value("uncounted").orElse(false)) store.setUnclear(session.id(), session.unclearAttempts() + 1);
        return Map.of("repeat", true);
    }

    private Map<String, Object> storeAnswer(State s) {
        Session session = store.load(id(s));
        String decided = s.<String>value("decision").orElse("UNCLEAR");
        if (CallScriptTags.LANGUAGE_QUESTION.equals(session.currentQuestion()) && Phrases.LANGUAGES.contains(decided)) store.setLanguage(session.id(), decided);
        store.saveAnswer(session.id(), session.currentQuestion(), s.<String>value("questionText").orElse(""), s.<String>value("answer").orElse("").strip(),
                s.<String>value("decision").orElse("UNCLEAR"), s.<Double>value("confidence").orElse(null), s.<String>value("source").orElse("TEXT"), session.language());
        return Map.of();
    }

    /** The next question in order after this order number that applies to the customer's call-script tags, or null. */
    public static String nextApplicable(Questionnaire qn, int afterOrder, Set<String> mine) {
        return qn.questions().stream().filter(x -> x.order() > afterOrder && x.order() < CallScriptTags.FOLLOW_UP_ORDER && CallScriptTags.applies(x.key(), mine))   // follow-ups (order 900+) are reached only by a branch
                .min(java.util.Comparator.comparingInt(Question::order)).map(Question::key).orElse(null);
    }

    /** The first branch whose condition matches wins. No match: the next question in order, or the end if there is none. A question never repeats. */
    private Map<String, Object> resolveNext(State s) {
        Session session = store.load(id(s));
        Questionnaire qn = session.questionnaire();
        Question current = qn.find(session.currentQuestion());
        String decision = s.<String>value("decision").orElse("UNCLEAR");
        String next = null;
        boolean matched = false;
        List<Branch> branches = current.branches() == null ? List.of() : current.branches();
        for (Branch b : branches) {
            if ("ANY".equalsIgnoreCase(b.when()) || b.when().equalsIgnoreCase(decision)) {
                next = b.next();
                matched = true;
                break;
            }
        }
        Set<String> mine = tags.tagsFor(session.policyNo());
        if (!matched) {
            next = nextApplicable(qn, current.order(), mine);
        } else if (next != null && qn.find(next) != null && !CallScriptTags.applies(next, mine)) {
            next = nextApplicable(qn, qn.find(next).order(), mine);                    // the branch points at a question this customer's tags skip
        }
        if (next != null && qn.find(next) == null) next = null;                       // a branch pointing at a missing question ends the survey
        if (next != null) {
            final String n = next;
            boolean already = store.answers(session.id()).stream().anyMatch(a -> a.questionKey().equals(n));
            if (already) next = null;                                                  // a loop in the configuration ends the survey
        }
        store.advance(session.id(), next);
        return next == null ? Map.of("finished", true) : Map.of("next", next);
    }
}
