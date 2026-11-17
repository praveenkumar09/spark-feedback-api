package com.aia.sparkfeedback.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The call-script tags of a customer ("[FTF][Shield][NC][NONENG]" in callscript_customer.call_script, found by proposal_num = policy number)
 * and the rule that decides which questions apply to them.
 *
 * Rule (no change to Strapi needed): a question whose key starts with a tag name and a dash, for example "noneng-english", is asked only to
 * customers who have that tag; "nftf+vcti-meeting" needs both tags and "ftf+vc|vcti-x" needs FTF and either VC or VCTI. Any other key (for example "gen1") is asked to everyone.
 */
@Service
public class CallScriptTags {

    /** The question that asks which language the customer prefers; the answer switches the rest of the session into that language. */
    public static final String LANGUAGE_QUESTION = "noneng-language";

    private static final Set<String> KNOWN = Set.of("ftf", "nftf", "vc", "vcti", "ti", "cash", "ilp", "shield", "nc", "roadshow", "rental",
            "shared", "offshore", "noneng", "tier1", "tier2");
    private static final Pattern TAG = Pattern.compile("\\[([^\\]]+)]");

    private final JdbcTemplate jdbc;
    private final String schema;

    public CallScriptTags(JdbcTemplate jdbc, @Value("${spark.db.schema:feedback}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** The customer's tags, lower case without spaces (TIER 1 becomes tier1). Empty when the policy has no call-script row. */
    public Set<String> tagsFor(String policyNo) {
        List<String> rows = jdbc.queryForList("SELECT call_script FROM " + schema + ".callscript_customer WHERE proposal_num = ? LIMIT 1", String.class, policyNo);
        Set<String> tags = new HashSet<>();
        if (rows.isEmpty() || rows.get(0) == null) return tags;
        Matcher m = TAG.matcher(rows.get(0));
        while (m.find()) tags.add(m.group(1).replace(" ", "").toLowerCase(Locale.ROOT));
        return tags;
    }

    /** True when the question is for everyone, or for a tag this customer has. */
    public static boolean applies(String questionKey, Set<String> tags) {
        int dash = questionKey.indexOf('-');
        if (dash <= 0) return true;
        String[] all = questionKey.substring(0, dash).toLowerCase(Locale.ROOT).split("\\+");
        for (String group : all) for (String n : group.split("\\|")) if (!KNOWN.contains(n)) return true;   // not a tag prefix: for everyone
        for (String group : all) {                                          // "ftf+vc|vcti-..." = FTF and (VC or VCTI)
            boolean any = false;
            for (String n : group.split("\\|")) any |= tags.contains(n);
            if (!any) return false;
        }
        return true;
    }

    /** Questions with order 900 or more are follow-ups: reached only by a branch, never by simply being next in order. */
    public static final int FOLLOW_UP_ORDER = 900;
}
