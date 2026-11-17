#!/usr/bin/env python3
"""Runs several customers at the same time against the live API with scripted typed answers, then checks that every session followed
exactly the path its own answers should produce and that no answer leaked into another session.
Usage: python3 scripts/parallel_test.py [http://localhost:8090]"""
import json, sys, threading, urllib.request, urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090"

def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")

# (policy, dob, language, scripted answers by question key, expected path)
CASES = [
    ("SG-NF-100234", "1988-03-14", "en",
     {"n1": "No, it was quite complicated", "n2": "The OTP kept failing", "n3": "Yes", "n5": "Yes, I think so", "n7": "Yes", "n8": "Definitely", "n9": "Nothing more"},
     ["n1", "n2", "n3", "n5", "n7", "n8", "n9"]),
    ("SG-NF-100555", "1995-05-21", "en",
     {"n1": "yes", "n3": "not really", "n4": "yes please", "n5": "no", "n6": "A cheaper plan", "n7": "no", "n8": "maybe not", "n9": "-"},
     ["n1", "n3", "n4", "n5", "n6", "n7", "n8", "n9"]),
    ("SG-FT-200415", "1979-07-25", "zh",
     {"f1": "是的", "f3": "是", "f4": "不太清楚", "f5": "费用部分", "f6": "没有", "f8": "会的", "f9": "没有了"},
     ["f1", "f3", "f4", "f5", "f6", "f8", "f9"]),
    ("SG-FT-200962", "1985-01-30", "ta",
     {"f1": "இல்லை", "f2": "அடையாள அட்டை காட்டவில்லை", "f3": "ஆம்", "f4": "ஆம்", "f6": "ஆம்", "f7": "கொஞ்சம் அவசரப்படுத்தினார்", "f8": "இல்லை", "f9": "இல்லை"},
     ["f1", "f2", "f3", "f4", "f6", "f7", "f8", "f9"]),
    ("SG-BC-300118", "1990-09-09", "ms",
     {"g1": "tidak", "g2": "Proses terlalu lama", "g3": "ya"},
     ["g1", "g2", "g3"]),
    ("SG-FT-200777", "1983-12-08", "en",
     {"f1": "yes", "f3": "yes", "f4": "what do you mean?", "f6": "no", "f8": "yes", "f9": "great service"},
     None),  # f4 is answered unclearly, then again unclearly twice: stored as UNCLEAR, then default order (f5 next)
]
results = {}

def run(case):
    policy, dob, lang, script, expected = case
    st, res = call("POST", "/api/auth/login", {"policyNo": policy, "dob": dob, "language": lang})
    assert st == 200, (policy, st, res)
    token, turn = res["token"], res["turn"]
    sid = turn["sessionId"]
    if turn["status"] == "COMPLETED":
        st, turn = call("POST", f"/api/sessions/{sid}/restart", token=token)
    asked = []
    guard = 0
    while turn["status"] == "IN_PROGRESS" and guard < 40:
        guard += 1
        q = turn["question"]
        asked.append(q["key"])
        text = script.get(q["key"], "yes")
        st, turn = call("POST", f"/api/sessions/{sid}/answer", {"questionKey": q["key"], "text": text, "source": "TEXT"}, token)
        assert st == 200, (policy, st, turn)
    results[policy] = (sid, token, turn, asked)

threads = [threading.Thread(target=run, args=(c,)) for c in CASES]
[t.start() for t in threads]
[t.join() for t in threads]

ok = True
for policy, dob, lang, script, expected in CASES:
    sid, token, turn, asked = results[policy]
    got = [a["questionKey"] for a in turn["answers"]]
    prefix = {"SG-NF": "n", "SG-FT": "f", "SG-BC": "g"}[policy[:5]]
    leak = [k for k in got if not k.startswith(prefix)]
    line = f"{policy} [{lang}] {turn['status']:<9} path={got}"
    if leak: ok = False; line += f"  LEAK {leak}"
    if expected is not None and got != expected: ok = False; line += f"  EXPECTED {expected}"
    if turn["status"] != "COMPLETED": ok = False; line += "  NOT COMPLETED"
    print(line)
    for a in turn["answers"]:
        print(f"    {a['questionKey']:<3} {a['decision']:<8} {a['source']:<5} {a['answer'][:50]}")

# a token must not open another customer's session
(a_sid, a_tok, _, _), (b_sid, b_tok, _, _) = results[CASES[0][0]], results[CASES[1][0]]
st, _ = call("GET", f"/api/sessions/{b_sid}", token=a_tok)
print("cross-session read ->", st, "(expected 403)")
st2, _ = call("GET", f"/api/sessions/{a_sid}")
print("no token ->", st2, "(expected 401)")
ok = ok and st == 403 and st2 == 401
st3, r3 = call("POST", "/api/auth/login", {"policyNo": "SG-NF-100234", "dob": "2000-01-01"})
print("wrong dob ->", st3, r3)
ok = ok and st3 == 401
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
