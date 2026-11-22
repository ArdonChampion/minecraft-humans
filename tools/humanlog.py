#!/usr/bin/env python3
"""Read the Humans plugin event log (plugins/Humans/logs/humans-*.jsonl[.gz]) and explain what the AI did.

usage:
  humanlog.py summary  [--since 2h] [--day 2026-11-22]      overview + things that look wrong
  humanlog.py timeline NAME [--since 1h] [--n 120]          what one human saw, chose and did
  humanlog.py chat     [--since 1h] [--n 80]                players' messages and the humans' replies
  humanlog.py plans    [NAME] [--since 3h] [--n 20]         planner goals, thoughts, steps, results
  humanlog.py errors   [--since 24h]                        exceptions and AI failures
  humanlog.py raw      [--type decide] [--h NAME] [--n 50]  raw JSON lines
Options: --dir PATH (default /home/minecraftuser/server/plugins/Humans/logs)
"""
import sys, os, json, gzip, glob, argparse, re
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

DEFAULT_DIR = "/home/minecraftuser/server/plugins/Humans/logs"


def parse_since(s):
    if not s:
        return None
    m = re.fullmatch(r"(\d+)([smhd])", s)
    if not m:
        return datetime.fromisoformat(s).replace(tzinfo=timezone.utc)
    n, u = int(m.group(1)), m.group(2)
    return datetime.now(timezone.utc) - timedelta(**{{"s": "seconds", "m": "minutes", "h": "hours", "d": "days"}[u]: n})


def load(args):
    files = sorted(glob.glob(os.path.join(args.dir, "humans-*.jsonl*")))
    if args.day:
        files = [f for f in files if args.day in f]
    since = parse_since(args.since)
    if since:  # skip whole days before `since`
        files = [f for f in files if re.search(r"(\d{4}-\d\d-\d\d)", f).group(1) >= since.strftime("%Y-%m-%d")]
    out = []
    for f in files:
        op = gzip.open if f.endswith(".gz") else open
        with op(f, "rt", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                try:
                    e = json.loads(line)
                except Exception:
                    continue
                if since and e.get("t", "") < since.strftime("%Y-%m-%dT%H:%M:%S"):
                    continue
                out.append(e)
    return out


def hhmm(e):
    return e.get("t", "")[11:19]


def short(s, n=110):
    s = str(s or "")
    return s if len(s) <= n else s[: n - 3] + "..."


def line(e):
    t, ty, h = hhmm(e), e.get("type"), e.get("h", "-")
    if ty == "decide":
        return f"{t} {h:8} DECIDE[{e.get('src')}] -> {e.get('chose')} (conf {e.get('conf', '-')}) hunger={e.get('hunger')} hp={e.get('hp')} | top: {', '.join(e.get('options', [])[:5])}"
    if ty == "task_start":
        return f"{t} {h:8} start {e.get('option')}: {short(e.get('text'), 90)}"
    if ty == "task_end":
        return f"{t} {h:8} {'ok  ' if e.get('status') == 'SUCCESS' else 'FAIL'} {e.get('task')} {e.get('secs')}s: {short(e.get('outcome'), 100)}"
    if ty == "chat_in":
        return f"{t} {h:8} <- {e.get('player')}: \"{short(e.get('text'), 80)}\" [{e.get('intent')}/{e.get('reply_kind')}] trust {e.get('trust_now', 0):.2f}"
    if ty == "chat_ignored":
        return f"{t} {h:8} (ignored, not addressed) {e.get('player')}: {short(e.get('text'), 70)}"
    if ty == "mind":
        act = ("KEEP CURRENT" if e.get("keep_current") else "") or " | ".join(e.get("steps", [])) or (("GOAL " + e.get("goal")) if e.get("goal") else "") or ("STOP" if e.get("stop") else "no action")
        if e.get("ignore"):
            return f"{t} {h:8} MIND ignored {e.get('heard')} ({e.get('ms')}ms)"
        return f"{t} {h:8} MIND heard {e.get('heard')} -> says \"{short(e.get('say'), 80)}\" | does: {short(act, 120)} ({e.get('ms')}ms)"
    if ty == "chat_heard":
        return f"{t} {h:8} <- {e.get('player')}: \"{short(e.get('text'), 80)}\"{' (named)' if e.get('named') else ''}{' [queued while busy]' if e.get('busy') else ''}"
    if ty == "say":
        return f"{t} {h:8} says: \"{short(e.get('line'), 100)}\""
    if ty == "request":
        return f"{t} {h:8} request {e.get('intent')} from {e.get('player')}: {e.get('result')} {e.get('reason') or ''}"
    if ty == "plan":
        if e.get("error"):
            return f"{t} {h:8} PLAN r{e.get('round')} ERROR {short(e.get('error'), 90)} ({e.get('ms')}ms)"
        return f"{t} {h:8} PLAN r{e.get('round')} [{e.get('title')}] {short(e.get('thought'), 120)} => {' | '.join(e.get('steps', []))}{' DONE' if e.get('done') else ''} ({e.get('ms')}ms)"
    if ty == "plan_step":
        return f"{t} {h:8}   step {e.get('status')}: {short(e.get('step'), 60)} -> {short(e.get('outcome'), 80)}"
    if ty == "goal_new":
        return f"{t} {h:8} NEW GOAL from {e.get('from')}: {short(e.get('text'), 100)}"
    if ty == "goal_end":
        return f"{t} {h:8} GOAL {e.get('result')}: {e.get('goal')} ({e.get('rounds')} rounds)"
    if ty == "interrupt":
        return f"{t} {h:8} !! {e.get('what')} (was: {e.get('task')})"
    if ty == "status":
        return f"{t} {h:8} status @{e.get('pos')} {short(e.get('task'), 50)} hp={e.get('hp')} hunger={e.get('hunger')} energy={e.get('energy')} goal={e.get('goal')}{' TRAPPED' if e.get('trapped') else ''}{' UNDERGROUND' if e.get('underground') else ''}"
    if ty in ("ai_error", "error"):
        return f"{t} {h:8} {ty.upper()} {e.get('api') or e.get('where', '')}: {short(e.get('error'), 140)}"
    if ty == "ai_call":
        return f"{t} {'-':8} ai {e.get('api')} {e.get('ms')}ms"
    return f"{t} {h:8} {ty} " + short(json.dumps({k: v for k, v in e.items() if k not in ('t', 'type', 'h', 'pos')}), 140)


def summary(ev):
    if not ev:
        print("no events"); return
    print(f"{len(ev)} events from {ev[0]['t'][:19]} to {ev[-1]['t'][:19]} UTC")
    print("by type:", dict(Counter(e["type"] for e in ev).most_common()))
    humans = sorted({e["h"] for e in ev if e.get("h")})
    print("\n== per human")
    for h in humans:
        hv = [e for e in ev if e.get("h") == h]
        dec = [e for e in hv if e["type"] == "decide"]
        ends = [e for e in hv if e["type"] == "task_end"]
        fails = [e for e in ends if e.get("status") == "FAIL"]
        src = Counter(e.get("src") for e in dec)
        top = Counter(e.get("chose") for e in dec).most_common(6)
        print(f"- {h}: {len(dec)} decisions {dict(src)}, {len(ends)} tasks ({len(fails)} failed), deaths {sum(e['type'] == 'death' for e in hv)}, "
              f"unstuck {sum(e['type'] == 'unstuck_teleport' for e in hv)}, said {sum(e['type'] == 'say' for e in hv)} lines")
        print(f"    most chosen: {top}")
        if fails:
            fo = Counter(re.sub(r"\d+", "N", e.get("outcome", "")) for e in fails).most_common(4)
            print(f"    top failures: {fo}")
    print("\n== looks weird")
    weird = 0
    for h in humans:
        dec = [e for e in ev if e.get("h") == h and e["type"] == "decide"]
        run, prev = 1, None
        for e in dec:
            run = run + 1 if e.get("chose") == prev else 1
            prev = e.get("chose")
            if run == 6:
                weird += 1; print(f"- {h} chose '{prev}' 6+ times in a row (around {hhmm(e)})")
        st = [e for e in ev if e.get("h") == h and e["type"] == "status"]
        for a, b, c in zip(st, st[1:], st[2:]):
            if a.get("pos") == b.get("pos") == c.get("pos") and a.get("nav"):
                weird += 1; print(f"- {h} didn't move for 3 minutes while navigating at {a.get('pos')} ({hhmm(a)}, task {a.get('task')})"); break
        fails = [e for e in ev if e.get("h") == h and e["type"] == "task_end" and e.get("status") == "FAIL" and e.get("secs", 99) < 3]
        if len(fails) >= 8:
            weird += 1; print(f"- {h} had {len(fails)} instant task failures (e.g. {short(fails[-1].get('outcome'), 80)})")
        low = [e for e in ev if e.get("h") == h and e["type"] == "status" and (e.get("hunger") or 20) < 5]
        if low:
            weird += 1; print(f"- {h} was starving (hunger<5) in {len(low)} status snapshots")
    for e in ev:
        if e["type"] == "goal_end" and e.get("result") == "gave_up":
            weird += 1; print(f"- {e.get('h')} gave up on goal '{e.get('goal')}' at {hhmm(e)}")
        if e["type"] == "error":
            weird += 1; print(f"- ERROR {hhmm(e)} {e.get('h')}: {short(e.get('error'), 120)}")
    if not weird:
        print("nothing obviously wrong")
    plans = [e for e in ev if e["type"] == "plan"]
    ai = [e for e in ev if e["type"] in ("ai_call", "ai_error")]
    print("\n== AI")
    for api in sorted({e.get("api") for e in ai}):
        calls = [e for e in ai if e.get("api") == api]
        errs = [e for e in calls if e["type"] == "ai_error"]
        ms = sorted(e.get("ms", 0) for e in calls if e["type"] == "ai_call")
        med = ms[len(ms) // 2] if ms else 0
        print(f"- {api}: {len(calls)} calls, {len(errs)} errors, median {med}ms" + (f", last error: {short(errs[-1].get('error'), 100)}" if errs else ""))
    if plans:
        print(f"- planner: {len(plans)} plans, {sum(1 for p in plans if p.get('error'))} failed, goals done {sum(1 for e in ev if e['type'] == 'goal_end' and e.get('result') == 'done')}, "
              f"gave up {sum(1 for e in ev if e['type'] == 'goal_end' and e.get('result') == 'gave_up')}")


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("cmd", nargs="?", default="summary")
    ap.add_argument("name", nargs="?")
    ap.add_argument("--since"); ap.add_argument("--day"); ap.add_argument("--n", type=int, default=0)
    ap.add_argument("--type"); ap.add_argument("--h"); ap.add_argument("--dir", default=DEFAULT_DIR)
    a = ap.parse_args()
    if a.cmd == "summary":
        a.since = a.since or ("24h" if not a.day else None)
        summary(load(a)); return
    ev = load(a)
    if a.cmd == "timeline":
        if not a.since and not a.day: a.since = "1h"; ev = load(a)
        sel = [e for e in ev if (e.get("h") or "").lower() == (a.name or "").lower() and e["type"] != "ai_call"]
        for e in sel[-(a.n or 120):]: print(line(e))
    elif a.cmd == "chat":
        sel = [e for e in ev if e["type"] in ("chat_heard", "mind", "chat_in", "chat_ignored", "say", "request", "goal_new")]
        for e in sel[-(a.n or 80):]: print(line(e))
    elif a.cmd == "plans":
        sel = [e for e in ev if e["type"] in ("goal_new", "plan", "plan_step", "goal_end") and (not a.name or (e.get("h") or "").lower() == a.name.lower())]
        for e in sel[-(a.n or 60):]: print(line(e))
    elif a.cmd == "errors":
        sel = [e for e in ev if e["type"] in ("error", "ai_error")]
        for e in sel[-(a.n or 60):]:
            print(line(e))
            if e.get("stack"): print("    " + e["stack"].strip().replace("\n", "\n    ")[:1500])
    elif a.cmd == "raw":
        sel = [e for e in ev if (not a.type or e["type"] == a.type) and (not a.h or (e.get("h") or "").lower() == a.h.lower())]
        for e in sel[-(a.n or 50):]: print(json.dumps(e, ensure_ascii=False))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
