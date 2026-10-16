import json, os, shutil, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor

B = os.environ["BENCH_DIR"]  # directory that holds logs/ and where results are written
CLAUDE = shutil.which("claude")
CLI_BIN = os.environ["KMPERTRACE_CLI_BIN"]  # directory that holds the kmpertrace-cli script
JAVA_DIR = os.path.dirname(os.path.realpath(shutil.which("java")))

SCEN = {
 "s1": dict(
   arch="PairingService.connect(mac) starts a Bluetooth connection through a vendor SDK. The SDK reports state changes (CONNECTING, CONNECTED) on its own callback thread. PairingUi calls connect when the user taps Pair. WeightStream reads weight packets from the connected device.",
   symptom="The app showed 'Connected to BB:22', but no weight data ever arrived. The user had tapped Pair twice, choosing AA:11 first and then BB:22."),
 "s4": dict(
   arch="PairingService.connect(mac) starts a Bluetooth connection through a vendor SDK. The SDK reports state changes (CONNECTING, CONNECTED) on its own callback thread. PairingUi calls connect when the user taps Pair on a device in the list.",
   symptom="The user tapped Pair on three scales within one second: AA:11, then BB:22, then CC:33. The app then listed CC:33 as connected twice and never listed AA:11. The user says AA:11 and CC:33 were powered on and BB:22 was switched off. Explain what the SDK actually connected and why the app shows this."),
 "s5": dict(
   arch="Checkout.run processes an order through CartService, Inventory, PaymentService, Shipping and Receipt. PaymentService calls a card gateway and retries when it times out. Many orders run at the same time.",
   symptom="A customer says their checkout failed and that they may have been charged twice. The log covers a batch of checkouts. Find which order failed, why, and whether a double charge is possible. The customer's order number is not known."),
 "s2": dict(
   arch="Uploader uploads files in chunks, several files in parallel, and retries a chunk when the server returns 503. UploadScreen shows a summary.",
   symptom="One upload out of six failed with a finalize error. The other five succeeded."),
 "s3": dict(
   arch="ProfileScreen.load fetches the profile, the activity feed and the contacts in parallel, then renders.",
   symptom="The profile screen takes about 3.3 seconds to load. The target is under 1 second."),
}
KF_HINT = " The log was written by the KmperTrace library. `kmpertrace-cli print --file app.log --span-attrs on --color off` renders all traces as trees. Two helper commands are also on PATH: `ktrace-errors app.log` lists the traces that contain a failed span, and `ktrace-show app.log <trace-id>` prints one trace tree."
CLI_HINT = " The log was written by the KmperTrace library. The `kmpertrace-cli` command is available. `kmpertrace-cli print --file app.log --span-attrs on --color off` renders it as trace trees."
COND = {"KF": "kmpertrace.log", "P": "plain.log", "PP": "plainplus.log", "K": "kmpertrace.log", "KC": "kmpertrace.log"}

def prompt(s, c):
    d = SCEN[s]
    p = (f"You are debugging an app. {d['arch']} Symptom: {d['symptom']} "
         "The log from the failing run is in app.log in the current directory. No source code is available, so use only the log. "
         "Find the root cause. Answer in at most 6 sentences: the root cause, the component responsible, and the log lines (timestamps) that show it. Do not modify any files.")
    if c == "KC": p += CLI_HINT
    if c == "KF": p += KF_HINT
    return p

def run(job):
    s, c, t = job
    d = tempfile.mkdtemp(prefix=f"bench_{s}_{c}_{t}_", dir=B + "/work")
    shutil.copy(f"{B}/logs/{s}/{COND[c]}", f"{d}/app.log")
    env = dict(os.environ)
    path = f"/usr/local/bin:/usr/bin:/bin:{JAVA_DIR}"
    if c in ("KC", "KF"): path += ":" + CLI_BIN
    if c == "KF": path += ":" + os.environ["KTRACE_TOOLS_BIN"]
    env["PATH"] = path
    t0 = time.time()
    cp = subprocess.run([CLAUDE, "-p", prompt(s, c), "--model", "sonnet", "--output-format", "json",
        "--tools", "Read,Bash,Grep,Glob", "--setting-sources", "", "--strict-mcp-config", "--disable-slash-commands",
        "--no-session-persistence", "--permission-mode", "bypassPermissions", "--max-budget-usd", "2"],
        cwd=d, env=env, capture_output=True, text=True, timeout=900)
    try:
        j = json.loads(cp.stdout)
    except Exception:
        j = {"error": cp.stdout[:500] + cp.stderr[:500]}
    u = j.get("usage", {})
    rec = dict(scenario=s, cond=c, trial=t, result=j.get("result"), turns=j.get("num_turns"), cost=j.get("total_cost_usd"),
               input=u.get("input_tokens"), cache_create=u.get("cache_creation_input_tokens"), cache_read=u.get("cache_read_input_tokens"),
               output=u.get("output_tokens"), secs=round(time.time() - t0, 1), err=j.get("error"))
    with open(B + "/" + os.environ.get("RESULTS", "results.jsonl"), "a") as f: f.write(json.dumps(rec) + "\n")
    shutil.rmtree(d, ignore_errors=True)
    return rec

if __name__ == "__main__":
    trials = int(sys.argv[1]); workers = int(sys.argv[2])
    scen = sys.argv[3].split(",") if len(sys.argv) > 3 else list(SCEN)
    os.makedirs(B + "/work", exist_ok=True)
    jobs = [(s, c, t) for t in range(1, trials + 1) for s in scen for c in COND if (c != "PP" or s in ("s1", "s4")) and (c != "KF" or s == "s5")]
    with ThreadPoolExecutor(workers) as ex:
        for r in ex.map(run, jobs):
            print(r["scenario"], r["cond"], r["trial"], "turns", r["turns"], "cost", r["cost"], "out", r["output"], "err", bool(r["err"]), flush=True)
