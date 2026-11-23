"""10 minute ka game: bazaar khulne par har 10 minute Nifty 50 ke shares aur NIFTYBEES ka andaza (agle 10 aur 30 minute upar ya neeche),
dummy paise se trade, 10 minute baad jaanch, aur din ke ant mein score. Har subah model pichhle 7 din ke
1-minute data se dobara seekhta hai.

GitHub par chalta hai (game.yml). Data 'game' release mein rehta hai:
  live.json     abhi ke andaze, khule dummy trade, aaj ka score (app ke liye)
  history.json  har din ka score
  today.jsonl   aaj ke saare andaze aur unka nateeja
  model.json    model ke weights

Sirf seekhne ke liye. Asli paisa lagane ki salah nahi.
"""
import csv
import io
import json
import math
import os
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import index_game  # noqa: E402
import study  # noqa: E402

IST = timezone(timedelta(hours=5, minutes=30))
HORIZONS = [10, 30]     # minute: agle 10 aur 30 minute ka andaza
TICK = 10               # har kitne minute andaza
BUY_ABOVE = 0.56        # itna pakka ho tabhi dummy kharido
PICKS = 3               # ek baar mein zyada se zyada itne share
STAKE = 10_000.0        # har trade mein dummy rupaye
COST_PCT = 0.10         # aane-jaane ka kul kharcha (brokerage, STT, charges, bid-ask) %, andaaza
FEATURES = ["r10", "r30", "rday", "rel10", "nifty10", "vol", "range30", "late", "news"]
DIR = sys.argv[1] if len(sys.argv) > 1 else "research/game"
RELEASE = "game"


def now():
    return datetime.now(IST)


def nifty50():
    raw = study.get("https://niftyindices.com/IndexConstituent/ind_nifty50list.csv", 2)
    if raw:
        try:
            rows = list(csv.DictReader(io.StringIO(raw.decode("utf-8", "ignore"))))
            out = [r["Symbol"].strip() for r in rows if r.get("Symbol")]
            if len(out) >= 40:
                return out
        except (KeyError, csv.Error):
            pass
    return study.FALLBACK


def minutes(symbol, rng):
    """1-minute bhaav: {din: [(minute, close, volume), ...]} India ke time mein."""
    url = ("https://query2.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=" + rng + "&interval=1m"
           + ("&crumb=" + study.CRUMB[0] if study.CRUMB[0] else ""))
    raw = study.get(url, 3)
    if not raw:
        return {}
    try:
        res = json.loads(raw)["chart"]["result"][0]
        ts = res["timestamp"]
        q = res["indicators"]["quote"][0]
        closes, vols = q["close"], q["volume"]
    except (KeyError, IndexError, TypeError, ValueError):
        return {}
    days = {}
    for t, c, v in zip(ts, closes, vols):
        if c is None:
            continue
        d = datetime.fromtimestamp(t, IST)
        m = d.hour * 60 + d.minute
        if m < 9 * 60 + 15 or m > 15 * 60 + 29:
            continue
        days.setdefault(d.strftime("%Y-%m-%d"), []).append((m, float(c), float(v or 0)))
    return days


def at_minute(bars, m):
    """Is minute tak ka aakhri bhaav (index)."""
    idx = None
    for i, b in enumerate(bars):
        if b[0] <= m:
            idx = i
        else:
            break
    return idx


def feats(bars, nbars, m, news=0.0):
    i = at_minute(bars, m)
    j = at_minute(nbars, m)
    if i is None or j is None or i < 30 or j < 10:
        return None

    def back(bs, k, mins):
        t = at_minute(bs, mins)
        return bs[t][1] if t is not None else bs[0][1]

    c = bars[i][1]
    r10 = (c / back(bars, i, m - 10) - 1) * 100
    r30 = (c / back(bars, i, m - 30) - 1) * 100
    rday = (c / bars[0][1] - 1) * 100
    n10 = (nbars[j][1] / back(nbars, j, m - 10) - 1) * 100
    v10 = sum(b[2] for b in bars if m - 10 < b[0] <= m)
    vall = sum(b[2] for b in bars if b[0] <= m)
    span = max(1, (m - bars[0][0]) / 10)
    vol = math.log((v10 + 1) / (vall / span + 1))
    hi = max(b[1] for b in bars if m - 30 < b[0] <= m)
    lo = min(b[1] for b in bars if m - 30 < b[0] <= m)
    rng = (hi - lo) / c * 100
    late = (m - (9 * 60 + 15)) / 375
    return {"r10": r10, "r30": r30, "rday": rday, "rel10": r10 - n10, "nifty10": n10, "vol": vol,
            "range30": rng, "late": late, "news": news, "price": c}


def sig(z):
    return 1 / (1 + math.exp(-max(-30, min(30, z))))


def prob(w, f):
    return sig(w["bias"] + sum(w.get(k, 0.0) * f[k] for k in FEATURES))


def train(rows, epochs=150, lr=0.05, l2=0.01):
    w = {k: 0.0 for k in FEATURES}
    w["bias"] = 0.0
    if not rows:
        return w
    n = len(rows)
    for _ in range(epochs):
        g = {k: 0.0 for k in FEATURES}
        gb = 0.0
        for f, y in rows:
            e = prob(w, f) - y
            gb += e
            for k in FEATURES:
                g[k] += e * f[k]
        w["bias"] -= lr * gb / n
        for k in FEATURES:
            w[k] -= lr * (g[k] / n + l2 * w[k])
    return w


def samples(hist, nifty, days, h):
    rows = []
    for sym, byday in hist.items():
        for d in days:
            bars, nb = byday.get(d), nifty.get(d)
            if not bars or not nb or len(bars) < 60:
                continue
            for m in range(9 * 60 + 15 + 30, 15 * 60 + 30 - h, TICK):
                f = feats(bars, nb, m)
                if not f:
                    continue
                k = at_minute(bars, m + h)
                if k is None or bars[k][0] < m + h - 2:
                    continue
                rows.append((f, 1.0 if bars[k][1] > f["price"] else 0.0, sym))
    return rows


def pct(n, d):
    return round(n * 100 / d, 1) if d else None


def load(name, default):
    try:
        with open(os.path.join(DIR, name)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save(name, data):
    with open(os.path.join(DIR, name), "w") as f:
        json.dump(data, f, indent=1)


def publish(names):
    paths = [os.path.join(DIR, n) for n in names if os.path.exists(os.path.join(DIR, n))]
    if not paths:
        return
    if subprocess.run(["gh", "release", "view", RELEASE], capture_output=True).returncode != 0:
        subprocess.run(["gh", "release", "create", RELEASE, "--title", "10 minute game",
                        "--notes", "Live dummy game data. For learning only, not investment advice."], capture_output=True)
    subprocess.run(["gh", "release", "upload", RELEASE, "--clobber"] + paths, capture_output=True)


def morning(symbols):
    """Pichhle 7 din ke 1-minute data se seekho. Imaandar jaanch: aakhri din chhod ke seekho, us din par jaancho."""
    hist = {}
    for s in symbols:
        hist[s] = minutes(s + ".NS", "7d")
        time.sleep(0.4)
    nifty = minutes("NIFTYBEES.NS", "7d")
    today = now().strftime("%Y-%m-%d")
    days = sorted(d for d in nifty if d < today)
    if len(days) < 3:
        return None
    test_days, train_days = days[-1:], days[:-1]
    weights, check = {}, {"day": test_days[0]}
    for h in HORIZONS:
        w_test = train([(f, y) for f, y, _ in samples(hist, nifty, train_days, h)])
        rows = samples(hist, nifty, test_days, h)
        n = len(rows)
        check[str(h)] = {
            "guesses": n,
            "model_pct": pct(sum(1 for f, y, _ in rows if (prob(w_test, f) > 0.5) == (y > 0.5)), n),
            "coin_pct": 50.0,
            "repeat_last_pct": pct(sum(1 for f, y, _ in rows if (f["r10"] > 0) == (y > 0.5)), n),
            "always_up_pct": pct(sum(y for _, y, _ in rows), n),
        }
        weights[str(h)] = train([(f, y) for f, y, _ in samples(hist, nifty, days, h)])
    try:
        idx = index_game.morning()
    except Exception as e:  # noqa: BLE001
        print("index model failed", e, flush=True)
        idx = {}
    model = {"made": now().strftime("%Y-%m-%d %H:%M"), "weights": weights, "train_days": days, "check": check, "index": idx}
    save("model.json", model)
    print("model", json.dumps(check), flush=True)
    return model


def news_now():
    """Pichhle 15 minute mein NSE par aayi khabar wale shares."""
    raw = study.get("https://www.nseindia.com/api/corporate-announcements?index=equities", 2)
    out = {}
    if not raw:
        return out
    try:
        items = json.loads(raw)
    except ValueError:
        return out
    if isinstance(items, dict):
        items = items.get("data", [])
    cut = now() - timedelta(minutes=15)
    for it in items if isinstance(items, list) else []:
        try:
            at = datetime.strptime((it.get("an_dt") or "").strip(), "%d-%b-%Y %H:%M:%S").replace(tzinfo=IST)
        except ValueError:
            continue
        if at >= cut and it.get("symbol"):
            out[it["symbol"]] = (it.get("desc") or "")[:80]
    return out


def news_stats():
    """10 saal ke news study ka nateeja (study-data release se), agar mila. desc -> 1 din mein Nifty ko harane ka %."""
    try:
        r = subprocess.run(["gh", "release", "download", "study-data", "-p", "news_patterns.json", "-D", DIR, "--clobber"],
                           capture_output=True, timeout=120)
        if r.returncode != 0:
            return {}
        with open(os.path.join(DIR, "news_patterns.json")) as f:
            pats = json.load(f).get("patterns", {})
        return {k[5:]: v[0]["beat"] for k, v in pats.items() if k.startswith("desc:") and v and v[0]}
    except (OSError, ValueError, subprocess.SubprocessError, KeyError, TypeError):
        return {}


def resolve(log, bars):
    for e in log:
        for h in HORIZONS:
            key = str(h)
            if key not in e["p"] or key in e["res"]:
                continue
            bs = bars.get(e["symbol"]) or []
            k = at_minute(bs, e["at"] + h)
            if k is None or bs[k][0] < e["at"] + h - 2:
                continue
            after = bs[k][1]
            move = round((after / e["price"] - 1) * 100, 3)
            r = {"after": round(after, 2), "move_pct": move,
                 "right": None if after == e["price"] else (after > e["price"]) == (e["p"][key] > 0.5)}
            if key in e.get("bought", []):
                r["pnl"] = round(STAKE * (move - COST_PCT) / 100, 2)
            e["res"][key] = r


def main():
    os.makedirs(DIR, exist_ok=True)
    study.yahoo_login()
    symbols = nifty50() + ["NIFTYBEES"]
    today = now().strftime("%Y-%m-%d")
    model = load("model.json", {})
    if not model.get("made", "").startswith(today) or "10" not in (model.get("weights") or {}) or "index" not in model:
        model = morning(symbols) or model
    if now().hour < 8:
        print("before 8 am: only the model was made", flush=True)
        publish(["model.json"])
        return
    blank = {**{k: 0.0 for k in FEATURES}, "bias": 0.0}
    weights = {str(h): (model.get("weights") or {}).get(str(h), blank) for h in HORIZONS}
    stats = news_stats()
    idx = index_game.Game(DIR, model.get("index"))

    log = []
    if os.path.exists(os.path.join(DIR, "today.jsonl")):
        with open(os.path.join(DIR, "today.jsonl")) as f:
            log = [json.loads(x) for x in f if x.strip()]
        if log and log[0].get("day") != today:
            log = []
    end = 12 * 60 + 25 if now().hour < 12 else 15 * 60 + 31
    handled = {e["at"] for e in log}
    marked = -1  # khule trade ka live bhaav aakhri baar kis minute liya
    ended_day = False
    while True:
        t = now()
        m = t.hour * 60 + t.minute
        if m >= end:
            ended_day = end > 15 * 60
            break
        if m < 9 * 60 + 15:
            time.sleep(30)
            continue
        # Har 10 minute ka khaana: 9:15, 9:25, 9:35 ... ek khaana ek hi baar
        tick = m - (m - (9 * 60 + 15)) % TICK
        if tick in handled:
            # Do khaano ke beech: har minute khule nakli trade ka live munafa/nuksaan
            if m != marked and handled:
                marked = m
                try:
                    if idx.mark():
                        last = max(handled)
                        save("live.json", {**live(log, model, last), "index": idx.live(last)})
                        publish(["live.json", "index_today.jsonl"])
                except Exception as e:  # noqa: BLE001
                    print("live mark failed", e, flush=True)
            time.sleep(20)
            continue
        handled.add(tick)
        news = news_now()
        nifty = minutes("NIFTYBEES.NS", "1d").get(today, [])
        if not nifty:
            print("no market data today (holiday?)", flush=True)
            break
        bars = {"NIFTYBEES": nifty}
        for s in symbols:
            if s != "NIFTYBEES":
                bars[s] = minutes(s + ".NS", "1d").get(today, [])
                time.sleep(0.25)
        resolve(log, bars)
        try:
            idx.tick(tick)
        except Exception as e:  # noqa: BLE001
            print("index tick failed", e, flush=True)
        fresh = []
        for s in symbols:
            f = feats(bars.get(s) or [], nifty, tick, 1.0 if s in news else 0.0)
            if not f:
                continue
            # Sirf wahi andaza jiska nateeja 3:30 se pehle aa sake
            p = {str(h): round(prob(weights[str(h)], f), 3) for h in HORIZONS if tick + h <= 15 * 60 + 30}
            if not p:
                continue
            desc = news.get(s, "")
            fresh.append({"day": today, "at": tick, "symbol": s, "price": round(f["price"], 2), "p": p, "res": {},
                          "bought": [], "news": desc, "news_beat_pct": stats.get(desc[:60]) if desc else None,
                          "f": {k: round(f[k], 3) for k in FEATURES}})
        for h in HORIZONS:
            key = str(h)
            ranked = sorted([e for e in fresh if key in e["p"]], key=lambda e: -e["p"][key])
            for e in ranked[:PICKS]:
                if e["p"][key] >= BUY_ABOVE:
                    e["bought"].append(key)
        log += fresh
        with open(os.path.join(DIR, "today.jsonl"), "w") as f:
            f.write("\n".join(json.dumps(e) for e in log))
        save("live.json", {**live(log, model, tick), "index": idx.live(tick)})
        publish(["live.json", "today.jsonl", "index_today.jsonl", "model.json"])
        print(datetime.now(IST).strftime("%H:%M"), "tick", tick, "guesses", len(fresh), "news", len(news), flush=True)
        time.sleep(60)

    if ended_day and log:
        hist = load("history.json", [])
        hist = [h for h in hist if h.get("day") != today]
        hist.append({"day": today, **score(log), "check": model.get("check"), "index": idx.score()})
        save("history.json", hist[-400:])
        save("live.json", {**live(log, model, None), "index": idx.live(None)})
        publish(["live.json", "today.jsonl", "index_today.jsonl", "history.json", "model.json"])


def score(log):
    """Har samay (10 / 30 min) ka hisaab, saath mein 3 seedhe tarike."""
    out = {}
    for h in HORIZONS:
        key = str(h)
        done = [e for e in log if key in e["res"] and e["res"][key]["right"] is not None]
        trades = [e for e in log if key in e["res"] and "pnl" in e["res"][key]]
        nifty = [e for e in log if e["symbol"] == "NIFTYBEES" and key in e["res"]]
        n = len(done)
        nifty_pnl = [STAKE * (e["res"][key]["move_pct"] - COST_PCT) / 100 for e in nifty]
        ranked = sorted(trades, key=lambda e: e["res"][key]["pnl"])
        row = {
            "guesses": n,
            "model_pct": pct(sum(1 for e in done if e["res"][key]["right"]), n),
            "coin_pct": 50.0,
            "repeat_last_pct": pct(sum(1 for e in done if (e["f"]["r10"] > 0) == (e["res"][key]["move_pct"] > 0)), n),
            "always_up_pct": pct(sum(1 for e in done if e["res"][key]["move_pct"] > 0), n),
            "trades": len(trades),
            "trades_won": sum(1 for e in trades if e["res"][key]["pnl"] > 0),
            "pnl": round(sum(e["res"][key]["pnl"] for e in trades), 2),
            "pnl_per_trade": round(sum(e["res"][key]["pnl"] for e in trades) / len(trades), 2) if trades else None,
            "nifty_buy_pnl_per_trade": round(sum(nifty_pnl) / len(nifty_pnl), 2) if nifty_pnl else None,
            "best": [brief(e, key) for e in ranked[-3:][::-1]],
            "worst": [brief(e, key) for e in ranked[:3]],
        }
        out[key] = row
    return out


def brief(e, key):
    r = e["res"][key]
    return {"symbol": e["symbol"], "at": "%d:%02d" % divmod(e["at"], 60), "price": e["price"], "after": r["after"],
            "move_pct": r["move_pct"], "pnl": r.get("pnl"), "p": e["p"][key], "news": e.get("news", "")}


def live(log, model, tick):
    latest = sorted([e for e in log if tick is not None and e["at"] == tick], key=lambda e: -e["p"].get("10", e["p"].get("30", 0)))
    open_now = [{"symbol": e["symbol"], "price": e["price"], "at": "%d:%02d" % divmod(e["at"], 60), "minutes": key, "p": e["p"][key]}
                for e in log for key in e.get("bought", []) if key not in e["res"]]
    return {"updated": now().strftime("%Y-%m-%d %H:%M"), "market_open": tick is not None,
            "today": score(log), "model_check": model.get("check"),
            "guesses": [{"symbol": e["symbol"], "price": e["price"], "p": e["p"], "news": e["news"],
                         "news_beat_pct": e.get("news_beat_pct")} for e in latest],
            "open_trades": open_now,
            "cost_pct": COST_PCT, "stake": STAKE, "note": "Dummy money. For learning only, not investment advice."}


if __name__ == "__main__":
    main()
