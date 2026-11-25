"""10 minute ka game: bazaar khulne par har 10 minute Nifty 50 ke shares aur NIFTYBEES ka andaza (agle 10 aur 30 minute upar ya neeche),
dummy paise se trade, baad mein jaanch, aur din ke ant mein score.

Har subah model pichhle ~4 hafte ke 1-minute data se seekhta hai, aur pichhle 5 din par imaandar jaanch (walk-forward) hoti hai:
har jaanch wale din se pehle ke data se seekho, us din par jaancho. Jaanch mein model kamzor nikle to us din
nakli trade band (andaze chalte rehte hain), aur app par wajah dikhti hai.

GitHub par chalta hai (game.yml). Data 'game' release mein rehta hai:
  live.json     abhi ke andaze, khule dummy trade, aaj ka score, trade band hone ki wajah, report (app ke liye)
  history.json  har din ka score
  today.jsonl   aaj ke saare andaze aur unka nateeja
  model.json    model ke weights aur jaanch
  runs.json     har run kab chala, kitne khaane hue

Sirf seekhne ke liye. Asli paisa lagane ki salah nahi.
"""
import csv
import io
import json
import os
import subprocess
import sys
import time
import urllib.parse
from datetime import datetime, timedelta, timezone

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import index_game  # noqa: E402
import study  # noqa: E402

IST = timezone(timedelta(hours=5, minutes=30))
HORIZONS = [10, 30]     # minute: agle 10 aur 30 minute ka andaza
TICK = 10               # har kitne minute andaza
START = 9 * 60 + 45     # pehla andaza 9:45 (pehle 30 minute ka bhaav chahiye, aur shuru ka uchhal chhodna hai)
BUY_ABOVE = 0.60        # itna pakka ho (upar ya neeche) tabhi dummy trade. Pehle 0.56 tha.
PICKS = 2               # ek khaane mein zyada se zyada itne naye trade
QUIET_PICKS = 1         # 12:00 se 1:30 (sust samay) mein
STAKE = 10_000.0        # har trade mein dummy rupaye (pakkapan ke hisaab se 1x se 1.5x)
SLIP_PCT = 0.02         # har taraf bid-ask / slippage %, andaaza
STOP_PCT = 0.5          # itna ulta gaya to stop-loss
TARGET_PCT = 0.8        # itna seedha gaya to munafa le lo
MAX_TRADES_DAY = 10     # din mein kul shares trade
MAX_PER_SYMBOL = 2      # ek share par din mein
MAX_PER_SECTOR = 2      # ek sector mein ek saath khule trade
DAY_LOSS_LIMIT = 500.0  # din ka shares nuksaan is had par pahunche to aage trade band
LAST_ENTRY = 15 * 60    # 3:00 ke baad naya trade nahi
LAST_EXIT = 15 * 60 + 25  # trade 3:25 tak band ho jaana chahiye
TREND_BLOCK = 1.0       # share aaj itna % gir chuka ho to kharido mat (aur itna chadh chuka ho to short mat karo)
FLAT_PCT = 0.05         # seekhte waqt itni chhoti chaal shor hai, chhod do
CHECK_DAYS = 5          # walk-forward jaanch kitne din par
GATE_MODEL_PCT = 52.0   # jaanch mein model kam se kam itna sahi ho
GATE_SURE_PCT = 55.0    # pakke andaze kam se kam itne sahi hon
GATE_SURE_N = 30        # aur pakke andaze kam se kam itne hon
FEATURES = ["r10", "r30", "rday", "rel10", "nifty10", "vol", "range30", "late", "late2", "gap", "prev", "vix", "expiry",
            "pos", "vwap"]
FAST = ["r10", "r30", "rel10", "nifty10", "vol", "range30", "late", "vwap"]  # doosra model: sirf abhi ki chaal
DIR = sys.argv[1] if len(sys.argv) > 1 else "research/game"
RELEASE = "game"

SECTORS = {}
for _sec, _names in {
    "BANK": "HDFCBANK ICICIBANK SBIN KOTAKBANK AXISBANK INDUSINDBK",
    "FIN": "BAJFINANCE BAJAJFINSV SHRIRAMFIN JIOFIN HDFCLIFE SBILIFE",
    "IT": "TCS INFY HCLTECH WIPRO TECHM LTIM",
    "FMCG": "HINDUNILVR ITC NESTLEIND BRITANNIA TATACONSUM",
    "AUTO": "MARUTI M&M TATAMOTORS TMPV BAJAJ-AUTO EICHERMOT HEROMOTOCO",
    "ENERGY": "RELIANCE ONGC BPCL COALINDIA NTPC POWERGRID",
    "METAL": "TATASTEEL JSWSTEEL HINDALCO",
    "PHARMA": "SUNPHARMA CIPLA DRREDDY APOLLOHOSP DIVISLAB",
    "INFRA": "LT ADANIENT ADANIPORTS ULTRACEMCO GRASIM SHREECEM",
}.items():
    for _s in _names.split():
        SECTORS[_s] = _sec

# Niyam kab badle (app par dikhta hai, taaki pata chale kis badlaav se fark pada)
RULES = [
    {"day": "2026-11-25", "text": "Bank Nifty par nakli trade band, sirf andaze"},
    {"day": "2026-11-26", "text": "Shares: 4 hafte ka data, 5 din ki jaanch, kamzor model ho to trade band"},
    {"day": "2026-11-26", "text": "Shares: had 56% se 60%, short bhi, stop-loss 0.5%, target 0.8%, din ki had ₹500 aur 10 trade"},
    {"day": "2026-11-26", "text": "Nifty option: had 60%, sirf badi khabar / bade uchhal wale din, stop-loss 15%, target 20%, din mein 3 trade"},
]


def now():
    return datetime.now(IST)


def hhmm(m):
    return "%d:%02d" % divmod(int(m), 60)


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


def _parse_minutes(raw, days):
    try:
        res = json.loads(raw)["chart"]["result"][0]
        ts = res["timestamp"]
        q = res["indicators"]["quote"][0]
        closes, vols = q["close"], q["volume"]
    except (KeyError, IndexError, TypeError, ValueError):
        return
    for t, c, v in zip(ts, closes, vols):
        if c is None:
            continue
        d = datetime.fromtimestamp(t, IST)
        m = d.hour * 60 + d.minute
        if m < 9 * 60 + 15 or m > 15 * 60 + 29:
            continue
        days.setdefault(d.strftime("%Y-%m-%d"), {})[m] = (m, float(c), float(v or 0))


def _yahoo(symbol):
    # "M&M" jaise naam URL mein todne se bachao (pehle M&M ka data kabhi nahi aata tha)
    return urllib.parse.quote(symbol, safe="^.-")


def minutes(symbol, rng="1d", back_days=None):
    """1-minute bhaav: {din: [(minute, close, volume), ...]} India ke time mein.
    back_days diya to Yahoo se 7-7 din ke tukdon mein utne din pichhe tak (1-minute data sirf ~30 din tak milta hai)."""
    days = {}
    crumb = ("&crumb=" + study.CRUMB[0]) if study.CRUMB[0] else ""
    base = "https://query2.finance.yahoo.com/v8/finance/chart/" + _yahoo(symbol) + "?interval=1m" + crumb
    if back_days:
        end = int(time.time())
        start = end - back_days * 86400
        p2 = end
        while p2 > start:
            p1 = max(start, p2 - 7 * 86400)
            raw = study.get(base + "&period1=%d&period2=%d" % (p1, p2), 3)
            if raw:
                _parse_minutes(raw, days)
            p2 = p1
            time.sleep(0.3)
    else:
        raw = study.get(base + "&range=" + rng, 3)
        if raw:
            _parse_minutes(raw, days)
    return {d: [v[m] for m in sorted(v)] for d, v in days.items()}


class Day:
    """Ek share ka ek din: tez hisaab ke liye pehle se jod-ghata (numpy)."""

    def __init__(self, bars):
        a = np.array(bars, dtype=float) if bars else np.zeros((0, 3))
        self.m = a[:, 0] if len(a) else np.zeros(0)
        self.c = a[:, 1] if len(a) else np.zeros(0)
        self.v = a[:, 2] if len(a) else np.zeros(0)
        self.cv = np.cumsum(self.v)
        self.cpv = np.cumsum(self.v * self.c)
        self.hi = np.maximum.accumulate(self.c) if len(a) else self.c
        self.lo = np.minimum.accumulate(self.c) if len(a) else self.c

    def idx(self, m):
        """Is minute tak ka aakhri bar (index), na ho to None."""
        i = int(np.searchsorted(self.m, m, side="right")) - 1
        return i if i >= 0 else None

    def price(self, m):
        i = self.idx(m)
        return None if i is None else self.c[i]


def feats(day, nday, m, ctx, news=0.0):
    """ctx: prev_close, prev (kal ka %), vix, expiry."""
    i, j = day.idx(m), nday.idx(m)
    if i is None or j is None or i < 30 or j < 10:
        return None
    c = day.c[i]
    p10, p30 = day.price(m - 10), day.price(m - 30)
    n10 = nday.price(m - 10)
    if p10 is None or p30 is None or n10 is None:
        return None
    r10 = (c / p10 - 1) * 100
    r30 = (c / p30 - 1) * 100
    rday = (c / day.c[0] - 1) * 100
    nif10 = (nday.c[j] / n10 - 1) * 100
    k10 = day.idx(m - 10)
    v10 = day.cv[i] - (day.cv[k10] if k10 is not None else 0)
    span = max(1.0, (m - day.m[0]) / 10)
    vol = float(np.log((v10 + 1) / (day.cv[i] / span + 1)))
    lo30 = day.idx(m - 30) or 0
    w = day.c[lo30 + 1:i + 1] if i > lo30 else day.c[i:i + 1]
    rng = (w.max() - w.min()) / c * 100
    late = (m - (9 * 60 + 15)) / 375
    pc = ctx.get("prev_close")
    gap = (day.c[0] / pc - 1) * 100 if pc else 0.0
    span_hl = day.hi[i] - day.lo[i]
    pos = ((c - day.lo[i]) / span_hl - 0.5) if span_hl > 0 else 0.0
    vwap = day.cpv[i] / day.cv[i] if day.cv[i] > 0 else c
    return {"r10": r10, "r30": r30, "rday": rday, "rel10": r10 - nif10, "nifty10": nif10, "vol": vol,
            "range30": rng, "late": late, "late2": late * late, "gap": gap, "prev": ctx.get("prev", 0.0),
            "vix": ctx.get("vix", 0.0), "expiry": ctx.get("expiry", 0.0), "pos": pos, "vwap": (c / vwap - 1) * 100,
            "news": news, "price": float(c)}


# ---------- model: do logistic model (sab features + sirf tez chaal), dono sahmat hon tabhi pakka ----------

def train(rows, keys, epochs=400, lr=0.1, l2=0.01):
    w = {"keys": keys, "mu": [0.0] * len(keys), "sd": [1.0] * len(keys), "w": [0.0] * len(keys), "bias": 0.0}
    if not rows:
        return w
    X = np.array([[f[k] for k in keys] for f, _ in rows], dtype=float)
    y = np.array([t for _, t in rows], dtype=float)
    mu, sd = X.mean(0), X.std(0)
    sd[sd < 1e-9] = 1.0
    Z = np.clip((X - mu) / sd, -6, 6)
    wt, b = np.zeros(len(keys)), 0.0
    n = len(y)
    for _ in range(epochs):
        p = 1 / (1 + np.exp(-np.clip(Z @ wt + b, -30, 30)))
        e = p - y
        wt -= lr * (Z.T @ e / n + l2 * wt)
        b -= lr * e.mean()
    return {"keys": keys, "mu": mu.tolist(), "sd": sd.tolist(), "w": wt.tolist(), "bias": float(b)}


def prob1(w, f):
    if not w or "keys" not in w:
        return 0.5
    z = w["bias"]
    for k, mu, sd, wt in zip(w["keys"], w["mu"], w["sd"], w["w"]):
        z += wt * max(-6.0, min(6.0, (f.get(k, 0.0) - mu) / sd))
    return 1 / (1 + np.exp(-max(-30.0, min(30.0, z))))


def prob(pair, f):
    """Do model ka milan: dono ek disha mein hon to dono ka average, warna 0.5 (pata nahi)."""
    a, b = prob1(pair.get("all"), f), prob1(pair.get("fast"), f)
    if (a - 0.5) * (b - 0.5) <= 0:
        return 0.5
    return float((a + b) / 2)


def fit(rows):
    return {"all": train(rows, FEATURES), "fast": train(rows, FAST)}


def labelled(rows):
    """Seekhne ke liye: bahut chhoti chaal (shor) hata do."""
    return [(f, 1.0 if mv > 0 else 0.0) for f, mv in rows if abs(mv) >= FLAT_PCT]


def day_ctx(hist_sym, days_sorted, d, vix, exp):
    prev = [x for x in days_sorted if x < d and hist_sym.get(x)]
    ctx = {"vix": vix, "expiry": exp}
    if prev:
        pc = hist_sym[prev[-1]][-1][1]
        ctx["prev_close"] = pc
        if len(prev) > 1 and hist_sym.get(prev[-2]):
            ctx["prev"] = (pc / hist_sym[prev[-2]][-1][1] - 1) * 100
    return ctx


def samples(hist, nifty, days, h, vix_of):
    """[(features, move%, symbol)] har share, har din, har 10 minute."""
    rows = []
    alld = sorted(nifty)
    for sym, byday in hist.items():
        for d in days:
            bars, nb = byday.get(d), nifty.get(d)
            if not bars or not nb or len(bars) < 60:
                continue
            day, nday = Day(bars), Day(nb)
            ctx = day_ctx(byday, alld, d, vix_of(d), is_expiry(d))
            for m in range(START, LAST_EXIT - h + 1, TICK):
                f = feats(day, nday, m, ctx)
                if not f:
                    continue
                k = day.idx(m + h)
                if k is None or day.m[k] < m + h - 2:
                    continue
                rows.append((f, (day.c[k] / f["price"] - 1) * 100, sym))
    return rows


def is_expiry(d):
    """Nifty weekly expiry: Tuesday."""
    return 1.0 if datetime.strptime(d, "%Y-%m-%d").weekday() == 1 else 0.0


def pct(n, d):
    return round(n * 100 / d, 1) if d else None


def charges(value):
    """Shares intraday (MIS) ka kharcha ek trade ke liye (kharid + bikri), discount broker jaisa:
    brokerage 0.03% ya ₹20 (jo kam), STT 0.025% bikri par, exchange 0.00297%, SEBI 0.0001%, GST 18%, stamp 0.003% kharid par,
    aur dono taraf bid-ask/slippage."""
    brok = 2 * min(20.0, 0.0003 * value)
    stt = 0.00025 * value
    exch = 0.0000297 * 2 * value
    sebi = 0.000001 * 2 * value
    gst = 0.18 * (brok + exch + sebi)
    stamp = 0.00003 * value
    fees = brok + stt + exch + sebi + gst + stamp
    slip = 2 * SLIP_PCT / 100 * value
    return round(fees, 2), round(slip, 2)


def check_rows(pair, rows, h):
    """Jaanch: kitne sahi, pakke andaze kitne sahi, aur pakke andazon par nakli trade ka munafa (kharche ke baad)."""
    n = right = 0
    sure_n = sure_right = 0
    pnl = 0.0
    up = rep = 0
    for f, mv, _ in rows:
        if mv == 0:
            continue
        p = prob(pair, f)
        n += 1
        up += mv > 0
        rep += (f["r10"] > 0) == (mv > 0)
        right += (p > 0.5) == (mv > 0)
        if max(p, 1 - p) >= BUY_ABOVE:
            sure_n += 1
            ok = (p > 0.5) == (mv > 0)
            sure_right += ok
            fees, slip = charges(STAKE)
            pnl += STAKE * (mv if p > 0.5 else -mv) / 100 - fees - slip
    return {"guesses": n, "model_pct": pct(right, n), "coin_pct": 50.0, "repeat_last_pct": pct(rep, n),
            "always_up_pct": pct(up, n), "sure_guesses": sure_n, "sure_pct": pct(sure_right, sure_n),
            "sure_pnl": round(pnl, 2)}


def gate(check):
    """Model is din trade kare ya nahi, aur kyun. (ok, wajah)"""
    if not check or not check.get("guesses"):
        return False, "Model ki jaanch nahi ho paayi (data nahi mila)"
    mp, sp, sn = check.get("model_pct") or 0, check.get("sure_pct") or 0, check.get("sure_guesses") or 0
    if mp < GATE_MODEL_PCT:
        return False, "Pichhle %d din ki jaanch mein model sirf %d%% sahi tha (kam se kam %d%% chahiye)" % (CHECK_DAYS, round(mp), GATE_MODEL_PCT)
    if sn < GATE_SURE_N:
        return False, "Pichhle %d din mein pakke andaze bahut kam the (%d), bharosa nahi" % (CHECK_DAYS, sn)
    if sp < GATE_SURE_PCT:
        return False, "Pakke andaze jaanch mein sirf %d%% sahi the (kam se kam %d%% chahiye)" % (round(sp), GATE_SURE_PCT)
    if (check.get("sure_pnl") or 0) <= 0:
        return False, "Jaanch mein pakke andazon par bhi kharche ke baad nuksaan hota"
    return True, "Model jaanch mein paas hua (%d%% sahi, pakke %d%% sahi)" % (round(mp), round(sp))


def morning(symbols):
    """Pichhle ~4 hafte ke 1-minute data se seekho. Imaandar jaanch: aakhri 5 din, har din usse pehle ke data se seekh kar."""
    hist = {}
    for s in symbols:
        hist[s] = minutes(s + ".NS", back_days=29)
        time.sleep(0.3)
    nifty = hist.get("NIFTYBEES") or minutes("NIFTYBEES.NS", back_days=29)
    today = now().strftime("%Y-%m-%d")
    days = sorted(d for d in nifty if d < today)
    if len(days) < 6:
        return None
    vix = index_game.daily("^INDIAVIX")

    def vix_of(d):
        prev = max((x for x in vix if x < d), default=None)
        return ((vix[prev] if prev else 15) - 15) / 5

    test_days = days[-CHECK_DAYS:]
    weights, check = {}, {"days": test_days[0] + " to " + test_days[-1], "train_days": len(days)}
    for h in HORIZONS:
        allrows = {d: samples(hist, nifty, [d], h, vix_of) for d in days}
        parts = []
        for d in test_days:
            # Sirf us din se PEHLE ke data se seekho, phir us din par jaancho (imaandar jaanch)
            learn = [r[:2] for x in days if x < d for r in allrows[x]]
            parts.append(check_rows(fit(labelled(learn)), allrows[d], h))
        tot = sum(x["guesses"] for x in parts)
        sure = sum(x["sure_guesses"] for x in parts)

        def wavg(key, base):
            vals = [(x[key], x[base]) for x in parts if x[key] is not None and x[base]]
            s = sum(b for _, b in vals)
            return round(sum(a * b for a, b in vals) / s, 1) if s else None
        agg = {"guesses": tot, "model_pct": wavg("model_pct", "guesses"), "coin_pct": 50.0,
               "repeat_last_pct": wavg("repeat_last_pct", "guesses"), "always_up_pct": wavg("always_up_pct", "guesses"),
               "sure_guesses": sure, "sure_pct": wavg("sure_pct", "sure_guesses"),
               "sure_pnl": round(sum(x["sure_pnl"] for x in parts), 2),
               "by_day": [{"day": d, **{k: x[k] for k in ("guesses", "model_pct", "sure_guesses", "sure_pct", "sure_pnl")}}
                          for d, x in zip(test_days, parts)]}
        ok, why = gate(agg)
        agg["trade_ok"], agg["why"] = ok, why
        check[str(h)] = agg
        pair = fit(labelled([r[:2] for x in days for r in allrows[x]]))
        weights[str(h)] = pair
        # Kaunsa feature kitna asar karta hai (bada = zyada)
        check[str(h)]["features"] = sorted(
            [[k, round(abs(wt), 3)] for k, wt in zip(pair["all"]["keys"], pair["all"]["w"])], key=lambda x: -x[1])[:6]
    try:
        idx = index_game.morning()
    except Exception as e:  # noqa: BLE001
        print("index model failed", e, flush=True)
        idx = {}
    model = {"made": now().strftime("%Y-%m-%d %H:%M"), "weights": weights, "train_days": days, "check": check, "index": idx}
    save("model.json", model)
    print("model", json.dumps(check), flush=True)
    return model


# ---------- files ----------

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
    if not paths or os.environ.get("GAME_NO_PUBLISH"):
        return
    if subprocess.run(["gh", "release", "view", RELEASE], capture_output=True).returncode != 0:
        subprocess.run(["gh", "release", "create", RELEASE, "--title", "10 minute game",
                        "--notes", "Live dummy game data. For learning only, not investment advice."], capture_output=True)
    subprocess.run(["gh", "release", "upload", RELEASE, "--clobber"] + paths, capture_output=True)


# ---------- khabar aur chhutti ----------

def news_now():
    """Pichhle 15 minute mein NSE par aayi khabar wale shares: {symbol: (desc, kitne minute pehle, result hai?)}."""
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
    t = now()
    cut = t - timedelta(minutes=15)
    for it in items if isinstance(items, list) else []:
        try:
            at = datetime.strptime((it.get("an_dt") or "").strip(), "%d-%b-%Y %H:%M:%S").replace(tzinfo=IST)
        except ValueError:
            continue
        if at >= cut and it.get("symbol"):
            desc = (it.get("desc") or "")[:80]
            text = (desc + " " + (it.get("attchmntText") or "")).lower()
            out[it["symbol"]] = (desc, int((t - at).total_seconds() // 60), "financial result" in text or "results" in text)
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


def holiday_today():
    """NSE ki chhutti list mein aaj hai? (na mile to False, phir data se pata chalega)"""
    raw = study.get("https://www.nseindia.com/api/holiday-master?type=trading", 1)
    try:
        days = json.loads(raw).get("CM", [])
        today = now().strftime("%d-%b-%Y")
        return any((d.get("tradingDate") or "") == today for d in days)
    except (TypeError, ValueError, AttributeError):
        return False


# ---------- trade ka nateeja ----------

def exit_of(bars_day, at, h, side, force_end=False):
    """Trade kab aur kitne par band hua: stop-loss, target, samay poora, ya din khatam. (exit_minute, move%, reason) ya None."""
    sgn = 1 if side == "long" else -1
    entry_i = bars_day.idx(at)
    if entry_i is None:
        return None
    entry = bars_day.c[entry_i]
    end = min(at + h, LAST_EXIT)
    for i in range(entry_i + 1, len(bars_day.m)):
        m = bars_day.m[i]
        if m > end:
            break
        mv = (bars_day.c[i] / entry - 1) * 100 * sgn
        if mv <= -STOP_PCT:
            return int(m), -STOP_PCT, "stop"
        if mv >= TARGET_PCT:
            return int(m), TARGET_PCT, "target"
    k = bars_day.idx(end)
    if k is not None and bars_day.m[k] >= end - 2:
        return int(bars_day.m[k]), (bars_day.c[k] / entry - 1) * 100 * sgn, "time" if end == at + h else "day_end"
    if force_end and len(bars_day.m):
        return int(bars_day.m[-1]), (bars_day.c[-1] / entry - 1) * 100 * sgn, "day_end"
    return None


def resolve(log, bars, force_end=False):
    days = {s: Day(b) for s, b in bars.items() if b}
    for e in log:
        day = days.get(e["symbol"])
        if day is None:
            continue
        for h in HORIZONS:
            key = str(h)
            if key not in e["p"] or key in e["res"]:
                continue
            k = day.idx(e["at"] + h)
            r = None
            if k is not None and day.m[k] >= e["at"] + h - 2:
                after = float(day.c[k])
                move = round((after / e["price"] - 1) * 100, 3)
                r = {"after": round(after, 2), "move_pct": move,
                     "right": None if after == e["price"] else (after > e["price"]) == (e["p"][key] > 0.5)}
            elif force_end and len(day.m):
                after = float(day.c[-1])
                r = {"after": round(after, 2), "move_pct": round((after / e["price"] - 1) * 100, 3), "right": None,
                     "note": "din khatam, andaza poora nahi hua"}
            if key in e.get("bought", []):
                side = (e.get("side") or {}).get(key, "long")
                ex = exit_of(day, e["at"], h, side, force_end)
                if ex is None:
                    continue  # trade abhi khula hai
                m_out, mv, why = ex
                stake = (e.get("stake") or {}).get(key, STAKE)
                fees, slip = charges(stake)
                gross = stake * mv / 100
                r = r or {"after": round(float(day.price(m_out)), 2), "move_pct": round(mv if side == "long" else -mv, 3), "right": None}
                r.update({"pnl": round(gross - fees - slip, 2), "gross": round(gross, 2), "fees": fees, "slip": slip,
                          "exit": why, "exit_at": hhmm(m_out), "side": side, "stake": stake,
                          "trade_move_pct": round(mv, 3)})
                r["why_lost"] = loss_reason(r)
            if r is not None:
                e["res"][key] = r


def loss_reason(r):
    if r["pnl"] >= 0:
        return None
    if r["exit"] == "stop":
        return "stop-loss laga (bhaav ulta gaya)"
    if r["gross"] > 0:
        return "sahi disha, par kharche ne munafa kha liya"
    if r["exit"] == "day_end":
        return "din khatam, nuksaan mein band"
    return "disha galat nikli"


# ---------- trade lene ke niyam ----------

def can_trade_today(log):
    """Din ki had: (ok, wajah)."""
    trades = [(e, k) for e in log for k in e.get("bought", [])]
    if len(trades) >= MAX_TRADES_DAY:
        return False, "Aaj ke %d trade poore ho gaye" % MAX_TRADES_DAY
    lost = sum(e["res"][k]["pnl"] for e, k in trades if k in e["res"] and "pnl" in e["res"][k])
    if lost <= -DAY_LOSS_LIMIT:
        return False, "Aaj ka nuksaan %s ki had par pahuncha, aage trade band" % ("₹%d" % DAY_LOSS_LIMIT)
    return True, ""


def symbol_blocked(log, sym):
    mine = [(e, k) for e in log if e["symbol"] == sym for k in e.get("bought", [])]
    if len(mine) >= MAX_PER_SYMBOL:
        return True
    done = [e["res"][k]["pnl"] for e, k in sorted(mine, key=lambda x: x[0]["at"]) if k in e["res"] and "pnl" in e["res"][k]]
    return len(done) >= 2 and done[-1] < 0 and done[-2] < 0


def sector_busy(log, sym):
    sec = SECTORS.get(sym, sym)
    open_n = sum(1 for e in log for k in e.get("bought", []) if k not in e["res"] and SECTORS.get(e["symbol"], e["symbol"]) == sec)
    return open_n >= MAX_PER_SECTOR


def pick_trades(fresh, log, tick, gates):
    """Is khaane mein kaunse nakli trade lene hain. Har andaze par 'kyun nahi' bhi likha jaata hai."""
    ok_day, why_day = can_trade_today(log)
    picks = QUIET_PICKS if 12 * 60 <= tick < 13 * 60 + 30 else PICKS
    taken = 0
    for h in HORIZONS:
        key = str(h)
        ranked = sorted([e for e in fresh if key in e["p"]], key=lambda e: -max(e["p"][key], 1 - e["p"][key]))
        for e in ranked:
            p = e["p"][key]
            conf = max(p, 1 - p)
            if conf < BUY_ABOVE:
                break
            side = "long" if p > 0.5 else "short"
            reason = None
            if not gates.get(key, (False, ""))[0]:
                reason = gates.get(key, (False, "model jaanch mein fail"))[1]
            elif not ok_day:
                reason = why_day
            elif tick > LAST_ENTRY or tick + h > LAST_EXIT:
                reason = "Bazaar band hone ke bahut paas"
            elif e["symbol"] == "NIFTYBEES":
                reason = "NIFTYBEES sirf tulna ke liye"
            elif e.get("result_news"):
                reason = "Result ki khabar: bhaav ka andaza nahi lagta"
            elif symbol_blocked(log + fresh, e["symbol"]):
                reason = "Is share par aaj ke trade poore / lagatar 2 haar"
            elif sector_busy(log + fresh, e["symbol"]):
                reason = "Is sector mein pehle se 2 trade khule"
            elif side == "long" and e["f"]["rday"] <= -TREND_BLOCK:
                reason = "Share aaj tez gir raha hai, kharidna ulta daav"
            elif side == "short" and e["f"]["rday"] >= TREND_BLOCK:
                reason = "Share aaj tez chadh raha hai, short ulta daav"
            elif taken >= picks:
                reason = "Is khaane ke trade poore"
            if reason:
                e.setdefault("skip", {})[key] = reason
                continue
            e["bought"].append(key)
            e.setdefault("side", {})[key] = side
            e.setdefault("stake", {})[key] = round(STAKE * min(1.5, 1 + (conf - BUY_ABOVE) * 10), 0)
            taken += 1
            ok_day, why_day = can_trade_today(log + fresh)


# ---------- main ----------

def main():
    os.makedirs(DIR, exist_ok=True)
    started = now()
    runs = load("runs.json", [])
    run = {"day": started.strftime("%Y-%m-%d"), "start": started.strftime("%H:%M"), "ticks": 0}
    if holiday_today():
        print("NSE holiday today", flush=True)
        run["end"], run["note"] = now().strftime("%H:%M"), "chhutti"
        save("runs.json", (runs + [run])[-60:])
        publish(["runs.json"])
        return
    study.yahoo_login()
    symbols = nifty50() + ["NIFTYBEES"]
    today = now().strftime("%Y-%m-%d")
    model = load("model.json", {})
    w = model.get("weights") or {}
    if not model.get("made", "").startswith(today) or "all" not in (w.get("10") or {}) or "index" not in model:
        model = morning(symbols) or model
    if now().hour < 8:
        print("before 8 am: only the model was made", flush=True)
        publish(["model.json"])
        return
    weights = {str(h): (model.get("weights") or {}).get(str(h), {}) for h in HORIZONS}
    check = model.get("check") or {}
    gates = {str(h): (bool((check.get(str(h)) or {}).get("trade_ok")) and "all" in weights[str(h)],
                      (check.get(str(h)) or {}).get("why") or "Model ki jaanch nahi hui") for h in HORIZONS}
    stats = news_stats()
    vix = index_game.daily("^INDIAVIX")
    vprev = max((x for x in vix if x < today), default=None)
    base_ctx = {"vix": ((vix[vprev] if vprev else 15) - 15) / 5, "expiry": is_expiry(today)}
    prev_hist = {}
    idx = index_game.Game(DIR, model.get("index"))

    log = []
    if os.path.exists(os.path.join(DIR, "today.jsonl")):
        with open(os.path.join(DIR, "today.jsonl")) as f:
            log = [json.loads(x) for x in f if x.strip()]
        if log and log[0].get("day") != today:
            log = []
    end = 12 * 60 + 25 if now().hour < 12 else 15 * 60 + 31
    handled = {e["at"] for e in log}
    marked = -1
    ended_day = False
    lag = None
    bars = {}
    while True:
        t = now()
        m = t.hour * 60 + t.minute
        if m >= end:
            ended_day = end > 15 * 60
            break
        if m < START:
            time.sleep(30)
            continue
        tick = m - (m - START) % TICK
        if tick in handled or tick > 15 * 60 + 20:
            if m != marked and handled:
                marked = m
                try:
                    if idx.mark():
                        last = max(handled)
                        save("live.json", live(log, model, last, gates, idx, lag))
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
            run["note"] = "data nahi mila (chhutti?)"
            break
        lag = tick - int(nifty[-1][0])
        bars = {"NIFTYBEES": nifty}
        for s in symbols:
            if s != "NIFTYBEES":
                bars[s] = minutes(s + ".NS", "1d").get(today, [])
                time.sleep(0.2)
        if not prev_hist:
            # Kal ka band bhaav (gap aur kal ki chaal ke liye), din mein ek baar
            for s in symbols:
                prev_hist[s] = minutes(s + ".NS", "5d")
        resolve(log, bars)
        try:
            idx.tick(tick)
        except Exception as e:  # noqa: BLE001
            print("index tick failed", e, flush=True)
        nday = Day(nifty)
        fresh = []
        for s in symbols:
            b = bars.get(s) or []
            if not b:
                continue
            ph = prev_hist.get(s) or {}
            ctx = {**base_ctx, **day_ctx(ph, sorted(ph), today, base_ctx["vix"], base_ctx["expiry"])}
            nw = news.get(s)
            f = feats(Day(b), nday, tick, ctx, 1.0 if nw else 0.0)
            if not f:
                continue
            p = {str(h): round(prob(weights[str(h)], f), 3) for h in HORIZONS if tick + h <= 15 * 60 + 30}
            if not p:
                continue
            desc = nw[0] if nw else ""
            fresh.append({"day": today, "at": tick, "symbol": s, "price": round(f["price"], 2), "p": p, "res": {},
                          "bought": [], "news": desc, "news_age": nw[1] if nw else None, "result_news": bool(nw and nw[2]),
                          "news_beat_pct": stats.get(desc[:60]) if desc else None,
                          "f": {k: round(float(f[k]), 3) for k in FEATURES}})
        pick_trades(fresh, log, tick, gates)
        log += fresh
        run["ticks"] += 1
        with open(os.path.join(DIR, "today.jsonl"), "w") as f:
            f.write("\n".join(json.dumps(e) for e in log))
        save("live.json", live(log, model, tick, gates, idx, lag))
        publish(["live.json", "today.jsonl", "index_today.jsonl", "model.json"])
        print(datetime.now(IST).strftime("%H:%M"), "tick", tick, "guesses", len(fresh), "news", len(news), "lag", lag, flush=True)
        time.sleep(60)

    run["end"] = now().strftime("%H:%M")
    save("runs.json", (runs + [run])[-60:])
    if ended_day and log:
        # Din khatam: bache hue khule trade aakhri bhaav par band karo
        nifty = minutes("NIFTYBEES.NS", "1d").get(today, [])
        if nifty:
            final = {"NIFTYBEES": nifty}
            for s in {e["symbol"] for e in log}:
                if s != "NIFTYBEES":
                    final[s] = minutes(s + ".NS", "1d").get(today, [])
                    time.sleep(0.2)
            resolve(log, final, force_end=True)
        try:
            idx.close_all()
        except Exception as e:  # noqa: BLE001
            print("index close failed", e, flush=True)
        with open(os.path.join(DIR, "today.jsonl"), "w") as f:
            f.write("\n".join(json.dumps(e) for e in log))
        hist = load("history.json", [])
        hist = [h for h in hist if h.get("day") != today]
        hist.append({"day": today, **score(log), "check": model.get("check"), "index": idx.score(),
                     "gate": {k: v[1] for k, v in gates.items()}, "index_gate": idx.gate_why()})
        save("history.json", hist[-400:])
        save("live.json", live(log, model, None, gates, idx, lag))
        publish(["live.json", "today.jsonl", "index_today.jsonl", "history.json", "model.json", "runs.json"])
    else:
        publish(["runs.json"])


# ---------- hisaab ----------

def bucket(m):
    if m < 11 * 60:
        return "subah"
    if m < 13 * 60 + 30:
        return "dopahar"
    return "shaam"


def score(log):
    """Har samay (10 / 30 min) ka hisaab, saath mein seedhe tarike, aur trade ki poori jaanch."""
    out = {}
    for h in HORIZONS:
        key = str(h)
        done = [e for e in log if key in e["res"] and e["res"][key].get("right") is not None]
        trades = [e for e in log if key in e["res"] and "pnl" in e["res"][key]]
        nifty = [e for e in log if e["symbol"] == "NIFTYBEES" and key in e["res"] and e["res"][key].get("move_pct") is not None]
        n = len(done)
        fees0, slip0 = charges(STAKE)
        nifty_pnl = [STAKE * e["res"][key]["move_pct"] / 100 - fees0 - slip0 for e in nifty]
        ranked = sorted(trades, key=lambda e: e["res"][key]["pnl"])
        pnl = sum(e["res"][key]["pnl"] for e in trades)
        sure = [e for e in done if max(e["p"][key], 1 - e["p"][key]) >= BUY_ABOVE]
        with_news = [e for e in done if e.get("news")]
        by_sym, by_time, reasons = {}, {}, {}
        for e in trades:
            r = e["res"][key]
            s = by_sym.setdefault(e["symbol"], {"trades": 0, "pnl": 0.0})
            s["trades"] += 1
            s["pnl"] = round(s["pnl"] + r["pnl"], 2)
            if r.get("why_lost"):
                reasons[r["why_lost"]] = reasons.get(r["why_lost"], 0) + 1
        for e in done:
            b = by_time.setdefault(bucket(e["at"]), [0, 0])
            b[0] += 1
            b[1] += 1 if e["res"][key]["right"] else 0
        out[key] = {
            "guesses": n,
            "model_pct": pct(sum(1 for e in done if e["res"][key]["right"]), n),
            "coin_pct": 50.0,
            "repeat_last_pct": pct(sum(1 for e in done if (e["f"]["r10"] > 0) == (e["res"][key]["move_pct"] > 0)), n),
            "always_up_pct": pct(sum(1 for e in done if e["res"][key]["move_pct"] > 0), n),
            "sure_guesses": len(sure), "sure_pct": pct(sum(1 for e in sure if e["res"][key]["right"]), len(sure)),
            "news_guesses": len(with_news), "news_pct": pct(sum(1 for e in with_news if e["res"][key]["right"]), len(with_news)),
            "trades": len(trades),
            "trades_won": sum(1 for e in trades if e["res"][key]["pnl"] > 0),
            "pnl": round(pnl, 2),
            "pnl_gross": round(sum(e["res"][key].get("gross", e["res"][key]["pnl"]) for e in trades), 2),
            "fees": round(sum(e["res"][key].get("fees", 0) for e in trades), 2),
            "slip": round(sum(e["res"][key].get("slip", 0) for e in trades), 2),
            "pnl_per_trade": round(pnl / len(trades), 2) if trades else None,
            "nifty_buy_pnl_per_trade": round(sum(nifty_pnl) / len(nifty_pnl), 2) if nifty_pnl else None,
            "best": [brief(e, key) for e in ranked[-3:][::-1]],
            "worst": [brief(e, key) for e in ranked[:3]],
            "by_symbol": sorted([{"symbol": k, **v} for k, v in by_sym.items()], key=lambda x: x["pnl"]),
            "by_time": {k: {"guesses": v[0], "model_pct": pct(v[1], v[0])} for k, v in by_time.items()},
            "loss_reasons": reasons,
        }
    return out


def brief(e, key):
    r = e["res"][key]
    return {"symbol": e["symbol"], "at": hhmm(e["at"]), "minutes": int(key), "price": e["price"], "after": r["after"],
            "move_pct": r["move_pct"], "pnl": r.get("pnl"), "p": e["p"][key], "news": e.get("news", ""),
            "side": r.get("side", "long"), "exit": r.get("exit"), "exit_at": r.get("exit_at"), "why_lost": r.get("why_lost")}


def missed_ticks(log, tick):
    """Bazaar ke samay mein kaunse 10-minute khaane chhoot gaye (koi andaza nahi bana)."""
    if tick is None:
        upto = 15 * 60 + 15
    else:
        upto = tick
    have = {e["at"] for e in log}
    return [hhmm(m) for m in range(START, upto + 1, TICK) if m not in have]


def summary(hist, today_row, index_today):
    """Hafte / mahine ka hisaab, lagatar munafe/nuksaan ke din, sabse achha/bura din."""
    rows = [h for h in hist if h.get("day") != (today_row or {}).get("day")]
    if today_row:
        rows.append(today_row)

    def money(d):
        v = 0.0
        for k in ("10", "30"):
            v += (d.get(k) or {}).get("pnl", 0) or 0
        ix = (d.get("index") or {}).get("NIFTY") or {}
        for hh in ix.values():
            v += (hh or {}).get("pnl", 0) or 0
        return round(v, 2)

    def trades(d):
        n = sum((d.get(k) or {}).get("trades", 0) or 0 for k in ("10", "30"))
        ix = (d.get("index") or {}).get("NIFTY") or {}
        return n + sum((hh or {}).get("trades", 0) or 0 for hh in ix.values())

    days = [{"day": d["day"], "pnl": money(d), "trades": trades(d), "model_pct": (d.get("10") or {}).get("model_pct")} for d in rows]
    played = [d for d in days if d["trades"] > 0]

    def window(n):
        part = days[-n:]
        acc = [d["model_pct"] for d in part if d["model_pct"] is not None]
        return {"days": len(part), "pnl": round(sum(d["pnl"] for d in part), 2), "trades": sum(d["trades"] for d in part),
                "model_pct": round(sum(acc) / len(acc), 1) if acc else None}
    win_streak = lose_streak = 0
    for d in reversed(played):
        if d["pnl"] > 0 and lose_streak == 0:
            win_streak += 1
        elif d["pnl"] < 0 and win_streak == 0:
            lose_streak += 1
        else:
            break
    best = max(played, key=lambda d: d["pnl"]) if played else None
    worst = min(played, key=lambda d: d["pnl"]) if played else None
    return {"week": window(5), "month": window(22), "all": window(len(days)), "win_streak": win_streak, "lose_streak": lose_streak,
            "best_day": best, "worst_day": worst, "real_money_rule": "Lagatar 2-3 mahine (40-60 khel ke din) munafa, kharche ke baad",
            "chart": days[-30:]}


def lessons(sc, gates, idx_gate):
    """Aaj ki seekh, 2-4 aasaan line."""
    out = []
    s10, s30 = sc.get("10") or {}, sc.get("30") or {}
    for k, s in (("10", s10), ("30", s30)):
        mp = s.get("model_pct")
        if mp is not None and s.get("guesses", 0) >= 100:
            if mp < 50:
                out.append("%s minute wale andaze aaj sikke se bhi kam sahi (%d%%)." % (k, round(mp)))
            elif mp >= 55:
                out.append("%s minute wale andaze aaj achhe rahe (%d%%)." % (k, round(mp)))
    reasons = {}
    for s in (s10, s30):
        for r, n in (s.get("loss_reasons") or {}).items():
            reasons[r] = reasons.get(r, 0) + n
    if reasons:
        r, n = max(reasons.items(), key=lambda x: x[1])
        out.append("Sabse zyada nuksaan ki wajah: %s (%d baar)." % (r, n))
    syms = (s30.get("by_symbol") or []) + (s10.get("by_symbol") or [])
    if syms:
        w = min(syms, key=lambda x: x["pnl"])
        if w["pnl"] < 0:
            out.append("Sabse bura share: %s (−₹%d)." % (w["symbol"], round(-w["pnl"])))
    closed = [v[1] for v in gates.values() if not v[0]]
    if closed:
        out.append("Shares trade band: " + closed[0])
    if idx_gate:
        out.append("Nifty option: " + idx_gate)
    return out[:5]


def live(log, model, tick, gates, idx, lag):
    latest = sorted([e for e in log if tick is not None and e["at"] == tick], key=lambda e: -max(e["p"].get("10", 0.5), 1 - e["p"].get("10", 0.5)))
    open_now = [{"symbol": e["symbol"], "price": e["price"], "at": hhmm(e["at"]), "minutes": key, "p": e["p"][key],
                 "side": (e.get("side") or {}).get(key, "long")}
                for e in log for key in e.get("bought", []) if key not in e["res"]]
    sc = score(log)
    ix = idx.live(tick)
    today = now().strftime("%Y-%m-%d")
    hist = load("history.json", [])
    today_row = {"day": today, **sc, "index": ix.get("today")} if log else None
    skips = {}
    for e in log:
        for k, why in (e.get("skip") or {}).items():
            skips[why] = skips.get(why, 0) + 1
    return {"updated": now().strftime("%Y-%m-%d %H:%M"), "market_open": tick is not None,
            "today": sc, "model_check": model.get("check"),
            "gate": {k: {"ok": v[0], "why": v[1]} for k, v in gates.items()},
            "skipped": sorted([{"why": k, "count": v} for k, v in skips.items()], key=lambda x: -x["count"])[:6],
            "guesses": [{"symbol": e["symbol"], "price": e["price"], "p": e["p"], "news": e["news"],
                         "news_beat_pct": e.get("news_beat_pct")} for e in latest],
            "open_trades": open_now,
            "missed": missed_ticks(log, tick) if log else [],
            "data_lag_min": lag,
            "summary": summary(hist, today_row, ix.get("today")),
            "lessons": lessons(sc, gates, ix.get("gate_why")),
            "rules": RULES[::-1],
            "settings": {"sure": BUY_ABOVE, "stop_pct": STOP_PCT, "target_pct": TARGET_PCT, "day_loss_limit": DAY_LOSS_LIMIT,
                         "max_trades": MAX_TRADES_DAY},
            "cost_pct": round(sum(charges(STAKE)) * 100 / STAKE, 3), "stake": STAKE,
            "note": "Dummy money. For learning only, not investment advice.",
            "index": ix}


if __name__ == "__main__":
    main()
