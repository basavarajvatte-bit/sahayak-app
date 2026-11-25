"""Nifty aur Bank Nifty ka 10 minute game, dummy option trade ke saath (game.py isse chalata hai).

- Har 10 minute: agle 10 aur 30 minute index upar ya neeche, aur kitna pakka.
- Model pichhle 60 din ke 5-minute data se roz subah seekhta hai (aakhri 10 din par imaandar jaanch).
- Pakka andaza ho (upar 56%+ ya neeche 56%+) to NSE ke live option chain se 1 lot ATM Call ya Put
  asli ask bhaav par "khareedo", 10/30 minute baad asli bid bhaav par "becho". Saare kharche (brokerage,
  STT, exchange, GST, stamp) kaate jaate hain. Sabse paas wali expiry, lekin aaj ki expiry nahi.
Sirf dummy paisa, seekhne ke liye.
"""
import json
import math
import os
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import study  # noqa: E402

IST = timezone(timedelta(hours=5, minutes=30))
INDEXES = {"NIFTY": {"yahoo": "^NSEI", "lot": 65, "step": 50}, "BANKNIFTY": {"yahoo": "^NSEBANK", "lot": 30, "step": 100}}
HORIZONS = [10, 30]
SURE = 0.56
TRADE_ON = {"NIFTY"}  # Nakli trade sirf Nifty par (25 Nov 2026: Bank Nifty nahi khelna). Bank Nifty ke andaze chalte rahenge.
FEATURES = ["r10", "r30", "rday", "range30", "late", "gap", "prev", "vix", "expiry", "first15"]


def now():
    return datetime.now(IST)


def bars5(symbol, rng):
    """5-minute bhaav: {din: [(minute, open, high, low, close)]}."""
    url = ("https://query1.finance.yahoo.com/v8/finance/chart/" + urllib.request.quote(symbol) + "?range=" + rng + "&interval=5m")
    raw = study.get(url, 3)
    out = {}
    if not raw:
        return out
    try:
        res = json.loads(raw)["chart"]["result"][0]
        q = res["indicators"]["quote"][0]
        for t, o, h, l, c in zip(res["timestamp"], q["open"], q["high"], q["low"], q["close"]):
            if None in (o, h, l, c):
                continue
            d = datetime.fromtimestamp(t, IST)
            m = d.hour * 60 + d.minute
            if 9 * 60 + 15 <= m <= 15 * 60 + 25:
                out.setdefault(d.strftime("%Y-%m-%d"), []).append((m, o, h, l, c))
    except (KeyError, IndexError, TypeError, ValueError):
        pass
    for v in out.values():
        v.sort()
    return out


def daily(symbol):
    url = "https://query1.finance.yahoo.com/v8/finance/chart/" + urllib.request.quote(symbol) + "?range=6mo&interval=1d"
    raw = study.get(url, 3)
    out = {}
    if not raw:
        return out
    try:
        res = json.loads(raw)["chart"]["result"][0]
        for t, c in zip(res["timestamp"], res["indicators"]["quote"][0]["close"]):
            if c is not None:
                out[datetime.fromtimestamp(t, IST).strftime("%Y-%m-%d")] = c
    except (KeyError, IndexError, TypeError, ValueError):
        pass
    return out


def close_at(bs, m):
    """Is minute tak puri hui 5-minute candle ka close (candle m-5 se m tak)."""
    last = None
    for b in bs:
        if b[0] + 5 <= m:
            last = b[4]
        else:
            break
    return last


def feats(bs, m, prev_close, vix_prev, expiry):
    if not bs or prev_close is None:
        return None
    c = close_at(bs, m)
    c10, c30 = close_at(bs, m - 10), close_at(bs, m - 30)
    c15 = close_at(bs, 9 * 60 + 30)
    if c is None or c10 is None or c30 is None:
        return None
    o = bs[0][1]
    hi = max(b[2] for b in bs if m - 30 <= b[0] < m)
    lo = min(b[3] for b in bs if m - 30 <= b[0] < m)
    return {"r10": (c / c10 - 1) * 100, "r30": (c / c30 - 1) * 100, "rday": (c / o - 1) * 100,
            "range30": (hi - lo) / c * 100, "late": (m - 555) / 375, "gap": (o / prev_close - 1) * 100,
            "prev": 0.0, "vix": ((vix_prev or 15) - 15) / 5, "expiry": 1.0 if expiry else 0.0,
            "first15": (c15 / o - 1) * 100 if c15 else 0.0, "price": c}


def sig(z):
    return 1 / (1 + math.exp(-max(-30, min(30, z))))


def prob(w, f):
    return sig(w.get("bias", 0.0) + sum(w.get(k, 0.0) * f[k] for k in FEATURES))


def train(rows, epochs=300, lr=0.05, l2=0.02):
    w = {k: 0.0 for k in FEATURES}
    w["bias"] = 0.0
    n = len(rows)
    if not n:
        return w
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


def expiry_days(name):
    """Weekly expiry ke din (andaaza) taaki 'expiry' feature bane: Nifty Tuesday, Bank Nifty sirf mahine ka aakhri Tuesday."""
    def f(day):
        d = datetime.strptime(day, "%Y-%m-%d")
        if name == "NIFTY":
            return d.weekday() == 1
        return d.weekday() == 1 and (d + timedelta(days=7)).month != d.month
    return f


def samples(name, data, closes, vix, days, h):
    rows = []
    ds = sorted(data)
    isexp = expiry_days(name)
    for d in days:
        bs = data.get(d)
        if not bs or len(bs) < 60:
            continue
        i = ds.index(d)
        prev_day = max((x for x in closes if x < d), default=None)
        prev_close = closes.get(prev_day) if prev_day else None
        vix_prev = vix.get(max((x for x in vix if x < d), default=""), None)
        prev2 = max((x for x in closes if prev_day and x < prev_day), default=None)
        for m in range(9 * 60 + 45, 15 * 60 + 30 - h, 10):
            f = feats(bs, m, prev_close, vix_prev, isexp(d))
            later = close_at(bs, m + h)
            if not f or later is None:
                continue
            if prev2:
                f["prev"] = (closes[prev_day] / closes[prev2] - 1) * 100
            rows.append((f, 1.0 if later > f["price"] else 0.0))
        del i
    return rows


def pct(n, d):
    return round(n * 100 / d, 1) if d else None


def morning():
    out = {"made": now().strftime("%Y-%m-%d %H:%M"), "weights": {}, "check": {}}
    vix = daily("^INDIAVIX")
    today = now().strftime("%Y-%m-%d")
    for name, info in INDEXES.items():
        data = bars5(info["yahoo"], "60d")
        closes = daily(info["yahoo"])
        days = sorted(d for d in data if d < today)
        if len(days) < 20:
            continue
        test, learn = days[-10:], days[:-10]
        out["weights"][name] = {}
        out["check"][name] = {"days": test[0] + " to " + test[-1]}
        for h in HORIZONS:
            w_test = train(samples(name, data, closes, vix, learn, h))
            rows = samples(name, data, closes, vix, test, h)
            n = len(rows)
            out["check"][name][str(h)] = {
                "guesses": n, "model_pct": pct(sum(1 for f, y in rows if (prob(w_test, f) > 0.5) == (y > 0.5)), n),
                "coin_pct": 50.0, "repeat_last_pct": pct(sum(1 for f, y in rows if (f["r10"] > 0) == (y > 0.5)), n),
                "always_up_pct": pct(sum(y for _, y in rows), n)}
            out["weights"][name][str(h)] = train(samples(name, data, closes, vix, days, h))
        time.sleep(1)
    print("index model", json.dumps(out["check"]), flush=True)
    return out


def chain(name):
    """Sabse paas wali expiry (aaj ki nahi) ka option chain: (expiry, underlying, {strike: {CE:(bid,ask), PE:(bid,ask)}})."""
    study.get("https://www.nseindia.com/option-chain", 1)
    raw = study.get("https://www.nseindia.com/api/option-chain-contract-info?symbol=" + name, 2)
    try:
        exps = json.loads(raw)["expiryDates"]
    except (TypeError, ValueError, KeyError):
        return None
    today = now().date()
    exp = next((e for e in exps if datetime.strptime(e, "%d-%b-%Y").date() > today), None)
    if not exp:
        return None
    raw = study.get("https://www.nseindia.com/api/option-chain-v3?type=Indices&symbol=%s&expiry=%s" % (name, exp), 2)
    try:
        d = json.loads(raw)
        recs = d["records"]["data"]
        under = d["records"]["underlyingValue"]
    except (TypeError, ValueError, KeyError):
        return None
    strikes = {}
    for r in recs:
        k = r.get("strikePrice")
        row = {}
        for side in ("CE", "PE"):
            x = r.get(side) or {}
            bid, ask = x.get("buyPrice1") or 0, x.get("sellPrice1") or 0
            if bid > 0 and ask > 0:
                row[side] = (float(bid), float(ask), float(x.get("lastPrice") or 0))
        if k and row:
            strikes[float(k)] = row
    return {"expiry": exp, "under": under, "strikes": strikes}


def costs(buy_value, sell_value):
    """Option kharidne-bechne ka kul kharcha (andaaza, discount broker): brokerage 20+20, STT 0.1% bechne par,
    exchange 0.0355%, SEBI 0.0001%, GST 18% (brokerage + exchange + SEBI), stamp 0.003% kharid par."""
    brokerage = 40.0
    stt = 0.001 * sell_value
    exch = 0.000355 * (buy_value + sell_value)
    sebi = 0.000001 * (buy_value + sell_value)
    gst = 0.18 * (brokerage + exch + sebi)
    stamp = 0.00003 * buy_value
    return round(brokerage + stt + exch + sebi + gst + stamp, 2)


class Game:
    def __init__(self, folder, model):
        self.folder = folder
        self.model = model or {}
        self.log = []
        self.path = os.path.join(folder, "index_today.jsonl")
        today = now().strftime("%Y-%m-%d")
        if os.path.exists(self.path):
            with open(self.path) as f:
                self.log = [json.loads(x) for x in f if x.strip()]
            if self.log and self.log[0].get("day") != today:
                self.log = []
        self.vix = daily("^INDIAVIX")
        self.closes = {n: daily(i["yahoo"]) for n, i in INDEXES.items()}

    def tick(self, tick):
        today = now().strftime("%Y-%m-%d")
        vix_prev = self.vix.get(max((x for x in self.vix if x < today), default=""), None)
        for name, info in INDEXES.items():
            bs = bars5(info["yahoo"], "1d").get(today, [])
            # Option chain sirf tab: is index par trade hota ho, ya pehle ka koi trade abhi khula ho (use bechna hai)
            has_open = any(x for x in self.log if x["index"] == name and x.get("trade") and not x.get("done"))
            ch = chain(name) if (name in TRADE_ON or has_open) else None
            if not bs:
                continue
            closes = self.closes[name]
            prev_day = max((x for x in closes if x < today), default=None)
            prev2 = max((x for x in closes if prev_day and x < prev_day), default=None)
            # Purane andaze aur trade band karo
            for e in self.log:
                if e["index"] != name:
                    continue
                h = e["minutes"]
                if e.get("done") or e["at"] + h > tick:
                    continue
                later = close_at(bs, e["at"] + h)
                if later is None:
                    continue
                e["after"] = round(later, 2)
                e["move_pct"] = round((later / e["price"] - 1) * 100, 3)
                e["right"] = None if later == e["price"] else (later > e["price"]) == (e["p"] > 0.5)
                t = e.get("trade")
                if t and ch and t["strike"] in ch["strikes"] and t["side"] in ch["strikes"][t["strike"]] and t["expiry"] == ch["expiry"]:
                    bid = ch["strikes"][t["strike"]][t["side"]][0]
                    t["sell"] = bid
                    buy_v, sell_v = t["buy"] * t["qty"], bid * t["qty"]
                    t["costs"] = costs(buy_v, sell_v)
                    t["pnl"] = round(sell_v - buy_v - t["costs"], 2)
                elif t:
                    t["note"] = "Could not get the option price to close; not counted"
                e["done"] = True
            # Naya andaza
            f = feats(bs, tick, closes.get(prev_day), vix_prev, expiry_days(name)(today))
            if not f:
                continue
            if prev2:
                f["prev"] = (closes[prev_day] / closes[prev2] - 1) * 100
            for h in HORIZONS:
                if tick + h > 15 * 60 + 30:
                    continue
                w = (self.model.get("weights") or {}).get(name, {}).get(str(h))
                if not w:
                    continue
                p = round(prob(w, f), 3)
                e = {"day": today, "at": tick, "index": name, "minutes": h, "price": round(f["price"], 2), "p": p,
                     "f": {k: round(f[k], 3) for k in FEATURES}}
                busy = any(x for x in self.log if x["index"] == name and x["minutes"] == h and x.get("trade") and not x.get("done"))
                if ch and name in TRADE_ON and not busy and (p >= SURE or p <= 1 - SURE):
                    side = "CE" if p >= SURE else "PE"
                    atm = min(ch["strikes"], key=lambda k: abs(k - ch["under"]))
                    if side in ch["strikes"].get(atm, {}):
                        bid, ask = ch["strikes"][atm][side][0], ch["strikes"][atm][side][1]
                        e["trade"] = {"side": side, "strike": atm, "expiry": ch["expiry"], "buy": ask, "qty": info["lot"]}
                        self.mark_one(e["trade"], bid, ch["strikes"][atm][side][2], ch["under"])
                self.log.append(e)
        with open(self.path, "w") as fh:
            fh.write("\n".join(json.dumps(e) for e in self.log))

    def mark_one(self, t, bid, ltp, under):
        """Khule trade ka abhi ka hisaab: agar abhi bid par bechein to kharcha kaatkar kitna bachega."""
        buy_v, sell_v = t["buy"] * t["qty"], bid * t["qty"]
        t["now"] = bid
        t["now_ltp"] = ltp or None
        t["now_at"] = now().strftime("%H:%M")
        t["now_pnl"] = round(sell_v - buy_v - costs(buy_v, sell_v), 2)
        t["now_index"] = under

    def mark(self):
        """Har minute: khule nakli trade ka live bhaav (Zerodha jaisa). True = koi khula trade hai."""
        opened = [e for e in self.log if e.get("trade") and not e.get("done")]
        if not opened:
            return False
        for name in sorted({e["index"] for e in opened}):
            ch = chain(name)
            if not ch:
                continue
            for e in opened:
                t = e["trade"]
                if e["index"] != name or t["expiry"] != ch["expiry"]:
                    continue
                row = ch["strikes"].get(t["strike"], {}).get(t["side"])
                if row:
                    self.mark_one(t, row[0], row[2], ch["under"])
        with open(self.path, "w") as fh:
            fh.write("\n".join(json.dumps(e) for e in self.log))
        return True

    def score(self):
        out = {}
        for name in INDEXES:
            out[name] = {}
            for h in HORIZONS:
                rows = [e for e in self.log if e["index"] == name and e["minutes"] == h and e.get("done") and e.get("right") is not None]
                trades = [e for e in self.log if e["index"] == name and e["minutes"] == h and e.get("trade") and "pnl" in e["trade"]]
                n = len(rows)
                right_dir_loss = sum(1 for e in trades if e["right"] and e["trade"]["pnl"] < 0)
                out[name][str(h)] = {
                    "guesses": n, "model_pct": pct(sum(1 for e in rows if e["right"]), n), "coin_pct": 50.0,
                    "repeat_last_pct": pct(sum(1 for e in rows if (e["f"]["r10"] > 0) == (e["move_pct"] > 0)), n),
                    "always_up_pct": pct(sum(1 for e in rows if e["move_pct"] > 0), n),
                    "trades": len(trades), "trades_won": sum(1 for e in trades if e["trade"]["pnl"] > 0),
                    "pnl": round(sum(e["trade"]["pnl"] for e in trades), 2),
                    "costs": round(sum(e["trade"]["costs"] for e in trades), 2),
                    "right_but_lost": right_dir_loss,
                }
        return out

    def live(self, tick):
        latest = {}
        for e in self.log:
            if e["at"] == tick:
                latest.setdefault(e["index"], {})[str(e["minutes"])] = e["p"]
        price = {e["index"]: e["price"] for e in self.log if e["at"] == tick}
        trades = []
        for e in self.log:
            t = e.get("trade")
            if not t:
                continue
            trades.append({"index": e["index"], "at": "%d:%02d" % divmod(e["at"], 60), "minutes": e["minutes"],
                           "option": "%s %d %s" % (e["index"], t["strike"], t["side"]), "expiry": t["expiry"],
                           "qty": t["qty"], "buy": t["buy"], "sell": t.get("sell"), "costs": t.get("costs"),
                           "pnl": t.get("pnl"), "index_move_pct": e.get("move_pct"), "open": not e.get("done"),
                           "now": t.get("now"), "now_ltp": t.get("now_ltp"), "now_at": t.get("now_at"),
                           "now_pnl": t.get("now_pnl"), "now_index": t.get("now_index")})
        live_pnl = [x["now_pnl"] for x in trades if x["open"] and x.get("now_pnl") is not None]
        return {"guesses": [{"index": k, "price": price.get(k), "p": v} for k, v in latest.items()],
                "open_pnl": round(sum(live_pnl), 2) if live_pnl else None, "open_count": len(live_pnl),
                "trades": trades[-20:], "today": self.score(), "model_check": self.model.get("check")}
