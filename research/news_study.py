"""Khabar aane ke din se hisaab: NSE par company ki har announcement ke baad share ne Nifty ke mukable kya kiya.

study.py ke saath GitHub par chalta hai. Har khabar ki category (NSE ki 'desc') aur kuch khaas shabdon
(bonus, split, buyback, dividend, order, acquisition...) ke hisaab se ginti hoti hai.
Nateeja: news_summary.md, news_patterns.json (app ke liye), news_events.csv (khaas khabrein).
"""
import csv
import json
import os
import re
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import study  # noqa: E402

IST = timezone(timedelta(hours=5, minutes=30))
STEPS = study.STEPS
NAMES = study.NAMES
YEARS = 10
MIN_COUNT = 30

# Khabar ke andar ke shabd -> hamara pattern. Pehla jo mile wahi.
KEYWORDS = [
    ("bonus", r"\bbonus\b"),
    ("split", r"\b(stock split|split of|sub-?division)\b"),
    ("buyback", r"\bbuy-?\s?back\b"),
    ("rights", r"\brights issue\b"),
    ("fundraise", r"\b(qip|qualified institutions? placement|preferential (issue|allotment)|fund raising)\b"),
    ("merger", r"\b(amalgamation|merger|demerger|scheme of arrangement)\b"),
    ("acquisition", r"\b(acquisition|acquire)\b"),
    ("order", r"\b(bagging|receiving of orders?|order win|bags? (an? )?order|letter of award|contract (worth|from|of))\b"),
    ("rating_up", r"\b(upgrade[sd]?)\b.*\brating|\brating\b.*\bupgrade"),
    ("rating_down", r"\b(downgrade[sd]?)\b.*\brating|\brating\b.*\bdowngrade"),
    ("resignation", r"\bresign"),
    ("auditor_change", r"\b(resignation|change) of (the )?(statutory )?auditor"),
    ("dividend", r"\b(interim|final|special)? ?dividend\b"),
    ("results", r"\b(financial results|quarterly results|audited results|unaudited results)\b"),
    ("pledge", r"\bpledge"),
    ("promoter_buy", r"\b(acquisition of shares|purchase of shares).*\bpromoter|\bpromoter.*\b(acquisition|purchase)"),
]
KEY_RX = [(k, re.compile(p, re.I)) for k, p in KEYWORDS]

LABELS = {
    "bonus": "Bonus shares", "split": "Stock split", "buyback": "Buyback", "rights": "Rights issue",
    "fundraise": "Fund raise (QIP, preferential)", "merger": "Merger / demerger", "acquisition": "Acquisition",
    "order": "New order / contract", "rating_up": "Credit rating upgrade", "rating_down": "Credit rating downgrade",
    "resignation": "Someone resigned", "auditor_change": "Auditor changed or resigned", "dividend": "Dividend news",
    "results": "Financial results", "pledge": "Promoter pledge news", "promoter_buy": "Promoter buying shares",
}

# Har din aane wali routine cheezein, ginti mein bekaar
SKIP_DESC = re.compile(r"^(updates|general updates|trading window|newspaper publication|copy of newspaper|"
                       r"certificate under sebi|compliances?-?|shareholders meeting|loss of share certificates|"
                       r"esop|esos|esps|disclosure under sebi takeover|record date|book closure|"
                       r"analysts/institutional investor meet|investor presentation|press release)", re.I)


def announcements(sym):
    """Pichhle 10 saal ki announcements, 2-2 saal ke tukdon mein."""
    out = []
    now = datetime.now(IST)
    for k in range(0, YEARS, 2):
        to = now - timedelta(days=365 * k)
        fr = now - timedelta(days=365 * (k + 2) - 1)
        url = ("https://www.nseindia.com/api/corporate-announcements?index=equities&symbol=" + urllib.request.quote(sym)
               + "&from_date=" + fr.strftime("%d-%m-%Y") + "&to_date=" + to.strftime("%d-%m-%Y"))
        raw = study.get(url, 2)
        time.sleep(0.5)
        if not raw:
            continue
        try:
            items = json.loads(raw)
        except ValueError:
            continue
        if isinstance(items, dict):
            items = items.get("data", [])
        for it in items if isinstance(items, list) else []:
            try:
                at = datetime.strptime((it.get("an_dt") or "").strip(), "%d-%b-%Y %H:%M:%S")
            except ValueError:
                continue
            out.append((at, (it.get("desc") or "").strip(), (it.get("attchmntText") or "").strip()))
    return out


def base_index(days, at):
    """Khabar se pehle ka aakhri band bhaav: bazaar band hone (3:30) ke baad aayi to usi din ka, warna pichhle din ka."""
    day = at.strftime("%Y-%m-%d")
    after_close = at.hour * 60 + at.minute >= 15 * 60 + 30
    idx = None
    for i, d in enumerate(days):
        if d < day or (after_close and d == day):
            idx = i
        else:
            break
    return idx


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    study.yahoo_login()
    nifty = study.chart("NIFTYBEES.NS") or study.chart("^NSEI")
    if not nifty:
        sys.exit("Nifty data not found")
    nmap = dict(zip(nifty["days"], nifty["close"]))
    groups = {}  # naam -> list of [ (share, nifty) or None per step ]
    keyed_rows = []
    stocks = study.symbols()
    checked = 0
    total_news = 0
    for n, (sym, industry) in enumerate(stocks):
        news = announcements(sym)
        if not news:
            continue
        data = study.chart(sym + ".NS")
        time.sleep(0.4)
        if not data or len(data["days"]) < 100:
            continue
        checked += 1
        days, adj, close = data["days"], data["adj"], data["close"]
        seen = set()
        for at, desc, text in news:
            base = base_index(days, at)
            if base is None or base < 21:
                continue
            moves = []
            for k in STEPS:
                j = base + k
                if j < len(days) and days[base] in nmap and days[j] in nmap:
                    moves.append((adj[j] / adj[base] * 100 - 100, nmap[days[j]] / nmap[days[base]] * 100 - 100))
                else:
                    moves.append(None)
            if moves[0] is None:
                continue
            total_news += 1
            names = []
            if desc and not SKIP_DESC.match(desc):
                names.append("desc:" + desc[:60])
            blob = desc + " " + text
            key = next((k for k, rx in KEY_RX if rx.search(blob)), None)
            if key:
                names.append("key:" + key)
            fresh_key = False
            for name in names:
                # Ek hi din ki ek hi tarah ki kai announcements ek gino
                tag = (name, days[base])
                if tag in seen:
                    continue
                seen.add(tag)
                groups.setdefault(name, []).append(moves)
                if name.startswith("key:"):
                    fresh_key = True
            if fresh_key:
                row = {"symbol": sym, "industry": industry, "pattern": key, "announced": at.strftime("%Y-%m-%d %H:%M"),
                       "base_day": days[base], "price": round(close[base], 2),
                       "month_before_pct": round(adj[base] / adj[base - 21] * 100 - 100, 2), "news": text[:160]}
                for (k, name), mv in zip(zip(STEPS, NAMES), moves):
                    key2 = name.replace(" ", "_")
                    row["share_" + key2] = round(mv[0], 2) if mv else ""
                    row["nifty_" + key2] = round(mv[1], 2) if mv else ""
                keyed_rows.append(row)
        if n % 25 == 0:
            print(n, sym, total_news, len(keyed_rows), flush=True)

    def score(lst):
        out = []
        for s in range(len(STEPS)):
            pairs = [m[s] for m in lst if m[s] is not None]
            if not pairs:
                out.append(None)
                continue
            c = len(pairs)
            out.append({"count": c, "share": round(sum(p[0] for p in pairs) / c, 2), "nifty": round(sum(p[1] for p in pairs) / c, 2),
                        "up": round(sum(1 for p in pairs if p[0] > 0) * 100 / c), "beat": round(sum(1 for p in pairs if p[0] > p[1]) * 100 / c),
                        "median_gap": round(sorted(p[0] - p[1] for p in pairs)[c // 2], 2)})
        return out

    result = {}
    for name, lst in groups.items():
        if len(lst) >= MIN_COUNT:
            result[name] = score(lst)

    with open(os.path.join(out_dir, "news_patterns.json"), "w") as f:
        json.dump({"made": datetime.now(IST).strftime("%Y-%m-%d"), "steps": NAMES, "labels": LABELS,
                   "shares": checked, "news": total_news, "patterns": result}, f, indent=1)

    if keyed_rows:
        with open(os.path.join(out_dir, "news_events.csv"), "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(keyed_rows[0].keys()))
            w.writeheader()
            w.writerows(keyed_rows)

    def table(names, title):
        md = [f"## {title}\n", "| News | Events | 1 day | 1 week | 1 month | 3 months |", "|---|---|---|---|---|---|"]
        for name in names:
            sc = result[name]
            label = LABELS.get(name[4:], name[4:]) if name.startswith("key:") else name[5:]
            cells = [f"{s['beat']}% ({s['median_gap']:+.1f})" if s else "-" for s in sc]
            md.append(f"| {label} | {sc[0]['count']} | " + " | ".join(cells) + " |")
        return md + [""]

    keys = sorted([k for k in result if k.startswith("key:")], key=lambda k: -result[k][0]["count"])
    descs = [k for k in result if k.startswith("desc:") and result[k][2]]
    descs.sort(key=lambda k: -abs(result[k][2]["beat"] - 50))
    md = [f"# News study (from the day the news came out)\n\nShares: {checked}. News items: {total_news}. Years: {YEARS}.",
          "Each cell: how often the share beat Nifty, and in brackets the middle gap vs Nifty in %. 50% = coin toss.",
          "Counted from the last close before the news (news after 3:30 pm counts from that day's close).\n"]
    md += table(keys, "Our patterns")
    md += table(descs[:40], "NSE news types that differ most from a coin toss (1 month)")
    with open(os.path.join(out_dir, "news_summary.md"), "w") as f:
        f.write("\n".join(md))
    print("\n".join(md))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out")
