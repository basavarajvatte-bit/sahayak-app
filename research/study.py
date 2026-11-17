"""Pattern study ka purana data: Nifty 500 ke shares mein dividend aur split/bonus ke baad share ne kya kiya.

GitHub ke computer par chalta hai (wahan internet khula hai). Sirf Python ki apni cheezein, kuch install nahi.
Nateeja: events.csv (har event ek line) aur summary.md (har pattern ka hisaab).
"""
import csv
import http.cookiejar
import io
import re
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"}
STEPS = [1, 5, 21, 63]
NAMES = ["1 day", "1 week", "1 month", "3 months"]
YEARS = "10y"

FALLBACK = """RELIANCE TCS HDFCBANK INFY ICICIBANK HINDUNILVR ITC SBIN BHARTIARTL KOTAKBANK LT AXISBANK ASIANPAINT MARUTI
SUNPHARMA TITAN BAJFINANCE ULTRACEMCO WIPRO NESTLEIND HCLTECH TECHM POWERGRID NTPC ONGC COALINDIA TATASTEEL JSWSTEEL
HINDALCO GRASIM BAJAJFINSV ADANIPORTS DRREDDY CIPLA DIVISLAB EICHERMOT HEROMOTOCO BRITANNIA BPCL INDUSINDBK
TATAMOTORS SBILIFE HDFCLIFE APOLLOHOSP TATACONSUM UPL M&M BAJAJ-AUTO SHREECEM""".split()


OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
FAILS = {"yahoo": 0, "nse": 0}


def get(url, tries=4):
    for t in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with OPENER.open(req, timeout=30) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            if t == tries - 1:
                print("fail", url[:90], e, file=sys.stderr)
            time.sleep(5 * (2 ** t))
    return None


def nse_events(sym):
    """NSE ki corporate actions: dividend, bonus, split, buyback, rights (pichhle 10 saal)."""
    to = datetime.now(timezone.utc)
    url = ("https://www.nseindia.com/api/corporates-corporateActions?index=equities&symbol=" + urllib.request.quote(sym)
           + "&from_date=01-01-" + str(to.year - 10) + "&to_date=" + to.strftime("%d-%m-%Y"))
    raw = get(url, 2)
    if not raw:
        FAILS["nse"] += 1
        return None
    try:
        items = json.loads(raw)
    except ValueError:
        FAILS["nse"] += 1
        return None
    out = []
    for it in items if isinstance(items, list) else []:
        subject = (it.get("subject") or "").strip()
        low = subject.lower()
        try:
            date = datetime.strptime((it.get("exDate") or "").strip(), "%d-%b-%Y").strftime("%Y-%m-%d")
        except ValueError:
            continue
        if "bonus" in low:
            kind = "bonus"
        elif "split" in low or "sub-division" in low or "sub division" in low:
            kind = "split"
        elif "buy back" in low or "buyback" in low or "buy-back" in low:
            kind = "buyback"
        elif "rights" in low:
            kind = "rights"
        elif "dividend" in low:
            kind = "dividend"
        else:
            continue
        amount = 0.0
        if kind == "dividend":
            amount = sum(float(x) for x in re.findall(r"(?:rs|re)\.?\s*([0-9]+(?:\.[0-9]+)?)", low))
        out.append((kind, date, amount, subject[:80]))
    return out


def symbols():
    for url in ("https://niftyindices.com/IndexConstituent/ind_nifty500list.csv",
                "https://archives.nseindia.com/content/indices/ind_nifty500list.csv"):
        raw = get(url, 2)
        if raw:
            try:
                rows = list(csv.DictReader(io.StringIO(raw.decode("utf-8", "ignore"))))
                out = [(r["Symbol"].strip(), r.get("Industry", "").strip()) for r in rows if r.get("Symbol")]
                if len(out) > 100:
                    print("stock list from", url, len(out))
                    return out
            except (KeyError, csv.Error):
                pass
    print("stock list: fallback", len(FALLBACK))
    return [(s, "") for s in FALLBACK]


def chart(symbol):
    url = ("https://query2.finance.yahoo.com/v8/finance/chart/" + urllib.request.quote(symbol)
           + "?range=" + YEARS + "&interval=1d&events=div%2Csplit")
    raw = get(url)
    if not raw:
        return None
    try:
        res = json.loads(raw)["chart"]["result"][0]
        ts = res["timestamp"]
        q = res["indicators"]["quote"][0]["close"]
        adj = res["indicators"].get("adjclose", [{}])[0].get("adjclose") or q
        ev = res.get("events", {})
    except (KeyError, IndexError, TypeError, ValueError):
        return None
    days, close, adjc = [], [], []
    for t, c, a in zip(ts, q, adj):
        if c is None or a is None:
            continue
        days.append(datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%d"))
        close.append(c)
        adjc.append(a)
    return {"days": days, "close": close, "adj": adjc, "events": ev}


def day_of(ts):
    return datetime.fromtimestamp(int(ts), timezone.utc).strftime("%Y-%m-%d")


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    get("https://www.nseindia.com/get-quotes/equity?symbol=TCS", 1)  # NSE cookie
    nifty = chart("^NSEI")
    if not nifty:
        sys.exit("Nifty data not found")
    nmap = dict(zip(nifty["days"], nifty["close"]))
    rows = []
    stocks = symbols()
    ok = 0
    for n, (sym, industry) in enumerate(stocks):
        data = chart(sym + ".NS")
        time.sleep(0.4)
        if not data or len(data["days"]) < 100:
            continue
        ok += 1
        days, adj, close = data["days"], data["adj"], data["close"]
        found = nse_events(sym)
        time.sleep(0.4)
        if found is not None:
            # Ek hi din ke do dividend (final + special) ek gino
            merged = {}
            for kind, date, amount, note in found:
                k = (kind, date)
                if k in merged:
                    merged[k] = (kind, date, merged[k][2] + amount, merged[k][3])
                else:
                    merged[k] = (kind, date, amount, note)
            found = list(merged.values())
        else:
            found = []
            for d in (data["events"].get("dividends") or {}).values():
                found.append(("dividend", day_of(d["date"]), float(d.get("amount", 0)), ""))
            for sp in (data["events"].get("splits") or {}).values():
                found.append(("split", day_of(sp["date"]), 0.0, sp.get("splitRatio", "")))
        for kind, date, amount, ratio in found:
            i = next((k for k, d in enumerate(days) if d >= date), None)
            if i is None or i < 22:
                continue
            base = i - 1
            if base + 1 >= len(days):
                continue
            pre = adj[base] / adj[base - 21] - 1
            row = {"symbol": sym, "industry": industry, "type": kind, "date": date, "amount": round(amount, 4),
                   "note": ratio, "price": round(close[base], 2),
                   "yield_pct": round(amount / close[base] * 100, 3) if kind == "dividend" and close[base] else "",
                   "month_before_pct": round(pre * 100, 2)}
            for k, name in zip(STEPS, NAMES):
                j = base + k
                key = name.replace(" ", "_")
                if j < len(days) and days[base] in nmap and days[j] in nmap:
                    r = adj[j] / adj[base] - 1
                    nr = nmap[days[j]] / nmap[days[base]] - 1
                    row["share_" + key] = round(r * 100, 2)
                    row["nifty_" + key] = round(nr * 100, 2)
                else:
                    row["share_" + key] = ""
                    row["nifty_" + key] = ""
            rows.append(row)
        if n % 50 == 0:
            print(n, sym, len(rows), flush=True)

    fields = ["symbol", "industry", "type", "date", "amount", "note", "price", "yield_pct", "month_before_pct"]
    for name in NAMES:
        key = name.replace(" ", "_")
        fields += ["share_" + key, "nifty_" + key]
    with open(os.path.join(out_dir, "events.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    def score(group):
        lines = []
        for name in NAMES:
            key = name.replace(" ", "_")
            pairs = [(r["share_" + key], r["nifty_" + key]) for r in group if r["share_" + key] != ""]
            if not pairs:
                continue
            n = len(pairs)
            avg = sum(p[0] for p in pairs) / n
            navg = sum(p[1] for p in pairs) / n
            up = sum(1 for p in pairs if p[0] > 0) * 100 // n
            beat = sum(1 for p in pairs if p[0] > p[1]) * 100 // n
            lines.append(f"| {name} | {n} | {avg:+.2f}% | {navg:+.2f}% | {up}% | {beat}% |")
        return lines

    head = ["| After | Events | Share avg | Nifty avg | Went up | Beat Nifty |", "|---|---|---|---|---|---|"]
    groups = [
        ("Dividend (all)", [r for r in rows if r["type"] == "dividend"]),
        ("Dividend, yield 2% or more", [r for r in rows if r["type"] == "dividend" and r["yield_pct"] != "" and r["yield_pct"] >= 2]),
        ("Dividend, yield under 0.5%", [r for r in rows if r["type"] == "dividend" and r["yield_pct"] != "" and r["yield_pct"] < 0.5]),
        ("Dividend, share fell 5%+ in month before", [r for r in rows if r["type"] == "dividend" and r["month_before_pct"] <= -5]),
        ("Dividend, share rose 5%+ in month before", [r for r in rows if r["type"] == "dividend" and r["month_before_pct"] >= 5]),
        ("Bonus shares", [r for r in rows if r["type"] == "bonus"]),
        ("Stock split", [r for r in rows if r["type"] == "split"]),
        ("Buyback", [r for r in rows if r["type"] == "buyback"]),
        ("Rights issue", [r for r in rows if r["type"] == "rights"]),
    ]
    md = [f"# Pattern study data\n\nShares checked: {ok} of {len(stocks)}. Events: {len(rows)}. Years: {YEARS}. NSE list missing for {FAILS['nse']} shares (Yahoo events used).",
          "Counted from the day before the ex-date (the date the share trades without the dividend/split). Prices adjusted, so the dividend itself is included.\n"]
    for title, g in groups:
        md.append(f"## {title}\n")
        md += head + score(g) + [""]
    with open(os.path.join(out_dir, "summary.md"), "w") as f:
        f.write("\n".join(md))
    print("\n".join(md))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out")
