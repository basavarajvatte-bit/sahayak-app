"""Nifty aur Bank Nifty ka 10 minute game, dummy option trade ke saath (game.py isse chalata hai).

- Har 10 minute: agle 10 aur 30 minute index upar ya neeche, aur kitna pakka.
- Model pichhle 60 din ke 5-minute data se roz subah seekhta hai (aakhri 10 din par imaandar jaanch).
- Pakka andaza ho (upar 60%+ ya neeche 60%+) to NSE ke live option chain se 1 lot ATM Call ya Put
  asli ask bhaav par "khareedo", 10/30/60 minute baad (ya stop-loss / target lagne par) asli bid bhaav par "becho".
  Saare kharche (brokerage, STT, exchange, GST, stamp) kaate jaate hain. Sabse paas wali expiry, lekin aaj/kal ki nahi.
- Trade sirf tab: model pichhle 10 din ki jaanch mein paas ho, aaj "bada din" ho (badi khabar, bada gap ya VIX uchhla),
  index sapaat na ho, bid-ask farq kam ho, aur din ki had (3 trade, ₹3,000 nuksaan) poori na hui ho.
- Saath mein ek "chhaaya" trade: 1 strike ITM wala option, sirf tulna ke liye (ATM vs ITM kaunsa behtar).
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
HORIZONS = [10, 30, 60]
SURE = 0.60             # pehle 0.56 tha
TRADE_ON = {"NIFTY"}  # Nakli trade sirf Nifty par (25 Nov 2026: Bank Nifty nahi khelna). Bank Nifty ke andaze chalte rahenge.
STOP = 0.15             # premium 15% gire to bech do
TARGET = 0.20           # premium 20% chadhe to bech do
MAX_TRADES = 3          # din mein Nifty option trade
DAY_LOSS = 3000.0       # din ka option nuksaan is had par to band
MAX_SPREAD = 0.02       # bid-ask farq 2% se zyada to mat lo
FLAT_RANGE = 0.10       # pichhle 30 minute mein index itna % bhi na hila to option mat lo (bhaav pighalta hai)
GATE_PCT = 53.0         # 10 din ki jaanch mein kam se kam itna sahi
GATE_SURE_PCT = 55.0
GATE_SURE_N = 15
BIG_ONLY = True         # Sirf "bade din" par trade (aapka niyam: badi khabar wale din)
BIG_GAP = 0.8           # itna % gap = bada din
BIG_VIX = 8.0           # VIX kal itna % uchhla = bada din
BIG_MOVE = 1.0          # din mein index itna % chal chuka = bada din
BIG_WORDS = ["war", "attack", "terror", "airstrike", "missile", "government falls", "collapse", "no-confidence",
             "rbi policy", "repo rate", "budget", "emergency", "lockdown", "election result", "tariff", "sanction"]
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


def gate(c):
    """10 din ki jaanch mein model paas hua ya nahi: (ok, wajah)."""
    if not c.get("guesses"):
        return False, "Model ki jaanch nahi ho paayi"
    mp, sp, sn = c.get("model_pct") or 0, c.get("sure_pct") or 0, c.get("sure_guesses") or 0
    if mp < GATE_PCT:
        return False, "10 din ki jaanch mein model sirf %.1f%% sahi (kam se kam %d%% chahiye)" % (mp, GATE_PCT)
    if sn < GATE_SURE_N:
        return False, "10 din mein pakke andaze bahut kam (%d)" % sn
    if sp < GATE_SURE_PCT:
        return False, "Pakke andaze jaanch mein sirf %d%% sahi" % round(sp)
    return True, "Jaanch paas (%d%% sahi, pakke %d%%)" % (round(mp), round(sp))


def big_news():
    """Aaj market hilane wali badi khabar? Google News (India) ki aaj ki headlines mein bade shabd. (haan/nahi, headline)"""
    q = urllib.request.quote("(Sensex OR Nifty OR India) (" + " OR ".join('"%s"' % w for w in BIG_WORDS) + ") when:1d")
    raw = study.get("https://news.google.com/rss/search?q=" + q + "&hl=en-IN&gl=IN&ceid=IN:en", 2)
    if not raw:
        return False, ""
    import re
    titles = re.findall(r"<title>(.*?)</title>", raw.decode("utf-8", "ignore"))[1:]
    hits = [t for t in titles if any(w in t.lower() for w in BIG_WORDS) and any(x in t for x in ("Sensex", "Nifty", "market", "Market"))]
    return (len(hits) >= 2), (hits[0][:100] if hits else "")


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
            sure = [(f, y) for f, y in rows if max(prob(w_test, f), 1 - prob(w_test, f)) >= SURE]
            c = {"guesses": n, "model_pct": pct(sum(1 for f, y in rows if (prob(w_test, f) > 0.5) == (y > 0.5)), n),
                 "coin_pct": 50.0, "repeat_last_pct": pct(sum(1 for f, y in rows if (f["r10"] > 0) == (y > 0.5)), n),
                 "always_up_pct": pct(sum(y for _, y in rows), n),
                 "sure_guesses": len(sure), "sure_pct": pct(sum(1 for f, y in sure if (prob(w_test, f) > 0.5) == (y > 0.5)), len(sure))}
            c["trade_ok"], c["why"] = gate(c)
            out["check"][name][str(h)] = c
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


def is_open(e):
    """Trade abhi khula hai? Purane data mein 'closed' nahi hota: wahan 'pnl', 'note' ya 'done' matlab band."""
    t = e.get("trade")
    if not t:
        return False
    return not (t.get("closed") or "pnl" in t or t.get("note") or e.get("done"))


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
        self.news = None      # (haan/nahi, headline), din mein ek baar
        self.big = {}         # index -> wajah, agar aaj bada din hai
        self.gate_msg = ""

    # ---------- niyam ----------

    def model_ok(self, name, h):
        c = ((self.model.get("check") or {}).get(name) or {}).get(str(h)) or {}
        if "trade_ok" not in c:
            # Purana model (bina gate ke): jaanch ke number se khud tay karo
            ok, why = gate(c) if c else (False, "Model ki jaanch nahi mili")
            return ok, why
        return bool(c.get("trade_ok")), c.get("why") or ""

    def big_day(self, name, f, today):
        """Aaj bada din hai? Badi khabar, bada gap, VIX uchhla, ya index din mein bahut chal chuka."""
        if not BIG_ONLY:
            return "niyam band"
        if self.news is None:
            try:
                self.news = big_news()
            except Exception:  # noqa: BLE001
                self.news = (False, "")
        if self.news[0]:
            return "Badi khabar: " + self.news[1]
        if abs(f.get("gap", 0)) >= BIG_GAP:
            return "Bada gap (%.1f%%)" % f["gap"]
        days = sorted(x for x in self.vix if x < today)
        if len(days) >= 2 and self.vix[days[-2]]:
            ch = (self.vix[days[-1]] / self.vix[days[-2]] - 1) * 100
            if ch >= BIG_VIX:
                return "VIX kal %.0f%% uchhla" % ch
        if abs(f.get("rday", 0)) >= BIG_MOVE:
            return "Nifty aaj %.1f%% chal chuka" % f["rday"]
        return None

    def day_trades(self, name):
        return [e for e in self.log if e["index"] == name and e.get("trade")]

    def why_not(self, name, h, p, f, ch, today, tick):
        """Trade kyun nahi (None = trade le sakte hain)."""
        if name not in TRADE_ON:
            return "Is index par nakli trade band hai (sirf andaze)"
        if max(p, 1 - p) < SURE:
            return "Pakka nahi (%d%% se kam)" % round(SURE * 100)
        ok, why = self.model_ok(name, h)
        if not ok:
            return why
        big = self.big_day(name, f, today)
        if not big:
            return "Aaj koi badi khabar ya bada uchhal nahi (aapka niyam: sirf bade din)"
        self.big[name] = big
        if not ch:
            return "Option chain nahi mili"
        if (datetime.strptime(ch["expiry"], "%d-%b-%Y").date() - now().date()).days <= 1:
            return "Expiry kal hai: option ka bhaav bahut tez pighalta hai"
        if f.get("range30", 0) < FLAT_RANGE:
            return "Nifty sapaat chal raha hai: option kharidna ghaate ka sauda"
        if tick + h > 15 * 60 + 20 or tick >= 15 * 60:
            return "Bazaar band hone ke paas"
        mine = self.day_trades(name)
        if len(mine) >= MAX_TRADES:
            return "Aaj ke %d trade poore" % MAX_TRADES
        lost = sum(e["trade"].get("pnl", 0) or 0 for e in mine)
        if lost <= -DAY_LOSS:
            return "Aaj ka nuksaan ₹%d ki had par, band" % DAY_LOSS
        if any(is_open(e) for e in mine):
            return "Ek trade pehle se khula hai"
        return None

    def gate_why(self):
        return self.gate_msg

    # ---------- trade band karna ----------

    def close(self, e, bid, why):
        t = e["trade"]
        if t.get("closed") or "pnl" in t:
            return
        t["sell"] = bid
        buy_v, sell_v = t["buy"] * t["qty"], bid * t["qty"]
        t["costs"] = costs(buy_v, sell_v)
        t["pnl"] = round(sell_v - buy_v - t["costs"], 2)
        t["pnl_gross"] = round(sell_v - buy_v, 2)
        t["closed"] = True
        t["exit"] = why
        t["exit_at"] = now().strftime("%H:%M")

    def close_itm(self, t, ch):
        s = t.get("itm")
        if not s or "sell" in s or not ch:
            return
        row = ch["strikes"].get(s["strike"], {}).get(t["side"])
        if row:
            s["sell"] = row[0]
            bv, sv = s["buy"] * t["qty"], row[0] * t["qty"]
            s["pnl"] = round(sv - bv - costs(bv, sv), 2)

    def reason(self, e):
        """Nuksaan ki wajah, aasaan shabdon mein."""
        t = e.get("trade") or {}
        if t.get("pnl") is None or t["pnl"] >= 0:
            return None
        if t.get("exit") == "stop":
            return "stop-loss laga"
        if e.get("right"):
            return "index sahi disha mein, par option pighla / kharcha"
        if e.get("right") is None:
            return "index hila hi nahi, option pighla"
        return "index ulti disha mein gaya"

    def tick(self, tick):
        today = now().strftime("%Y-%m-%d")
        vix_prev = self.vix.get(max((x for x in self.vix if x < today), default=""), None)
        msgs = []
        for name, info in INDEXES.items():
            bs = bars5(info["yahoo"], "1d").get(today, [])
            has_open = any(is_open(x) for x in self.log if x["index"] == name)
            ch = chain(name) if (name in TRADE_ON or has_open) else None
            if not bs:
                continue
            closes = self.closes[name]
            prev_day = max((x for x in closes if x < today), default=None)
            prev2 = max((x for x in closes if prev_day and x < prev_day), default=None)
            # Purane andaze jaancho, samay poora hua trade becho
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
                if t and not t.get("closed") and "pnl" not in t:
                    row = ch["strikes"].get(t["strike"], {}).get(t["side"]) if ch and t["expiry"] == ch["expiry"] else None
                    if row:
                        self.close(e, row[0], "time")
                    else:
                        t["note"] = "Could not get the option price to close; not counted"
                        t["closed"] = True
                if t:
                    self.close_itm(t, ch)
                    t["why_lost"] = self.reason(e)
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
                why = self.why_not(name, h, p, f, ch, today, tick)
                if why is None:
                    side = "CE" if p >= 0.5 else "PE"
                    atm = min(ch["strikes"], key=lambda k: abs(k - ch["under"]))
                    row = ch["strikes"].get(atm, {}).get(side)
                    if not row:
                        why = "ATM option ka bhaav nahi mila"
                    elif (row[1] - row[0]) / row[1] > MAX_SPREAD:
                        why = "Bid-ask farq bahut zyada (%.1f%%)" % ((row[1] - row[0]) / row[1] * 100)
                    else:
                        e["trade"] = {"side": side, "strike": atm, "expiry": ch["expiry"], "buy": row[1], "qty": info["lot"],
                                      "big": self.big.get(name), "path": []}
                        # Chhaaya trade: 1 strike ITM (Call ke liye neeche wala, Put ke liye upar wala)
                        step = info["step"]
                        itm = atm - step if side == "CE" else atm + step
                        irow = ch["strikes"].get(itm, {}).get(side)
                        if irow:
                            e["trade"]["itm"] = {"strike": itm, "buy": irow[1]}
                        self.mark_one(e["trade"], row[0], row[2], ch["under"])
                if why and name in TRADE_ON and max(p, 1 - p) >= SURE:
                    e["skip"] = why
                    msgs.append(why)
                self.log.append(e)
        if msgs:
            self.gate_msg = msgs[-1]
        elif any(e.get("trade") for e in self.log):
            self.gate_msg = ""
        self.save()

    def save(self):
        with open(self.path, "w") as fh:
            fh.write("\n".join(json.dumps(e) for e in self.log))

    def mark_one(self, t, bid, ltp, under):
        """Khule trade ka abhi ka hisaab: agar abhi bid par bechein to kharcha kaatkar kitna bachega. Har minute ka bhaav bhi save."""
        buy_v, sell_v = t["buy"] * t["qty"], bid * t["qty"]
        t["now"] = bid
        t["now_ltp"] = ltp or None
        t["now_at"] = now().strftime("%H:%M")
        t["now_pnl"] = round(sell_v - buy_v - costs(buy_v, sell_v), 2)
        t["now_index"] = under
        t.setdefault("path", []).append([t["now_at"], bid])
        t["path"] = t["path"][-90:]

    def mark(self):
        """Har minute: khule nakli trade ka live bhaav, aur stop-loss / target. True = koi khula trade hai."""
        opened = [e for e in self.log if is_open(e)]
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
                if not row:
                    continue
                self.mark_one(t, row[0], row[2], ch["under"])
                if row[0] <= t["buy"] * (1 - STOP):
                    self.close(e, row[0], "stop")
                    self.close_itm(t, ch)
                elif row[0] >= t["buy"] * (1 + TARGET):
                    self.close(e, row[0], "target")
                    self.close_itm(t, ch)
        self.save()
        return True

    def close_all(self):
        """Din khatam: koi trade khula reh gaya to abhi ke bid par band."""
        opened = [e for e in self.log if is_open(e)]
        for name in sorted({e["index"] for e in opened}):
            ch = chain(name)
            for e in opened:
                t = e["trade"]
                if e["index"] != name:
                    continue
                row = ch["strikes"].get(t["strike"], {}).get(t["side"]) if ch and t["expiry"] == ch["expiry"] else None
                if row:
                    self.close(e, row[0], "day_end")
                    self.close_itm(t, ch)
                else:
                    t["closed"] = True
                    t["note"] = "Could not get the option price to close; not counted"
        self.save()

    def score(self):
        out = {}
        for name in INDEXES:
            out[name] = {}
            for h in HORIZONS:
                rows = [e for e in self.log if e["index"] == name and e["minutes"] == h and e.get("done") and e.get("right") is not None]
                trades = [e for e in self.log if e["index"] == name and e["minutes"] == h and e.get("trade") and "pnl" in e["trade"]]
                sure = [e for e in rows if max(e["p"], 1 - e["p"]) >= SURE]
                n = len(rows)
                right_dir_loss = sum(1 for e in trades if e.get("right") and e["trade"]["pnl"] < 0)
                itm = [e["trade"]["itm"]["pnl"] for e in trades if (e["trade"].get("itm") or {}).get("pnl") is not None]
                reasons = {}
                for e in trades:
                    r = e["trade"].get("why_lost")
                    if r:
                        reasons[r] = reasons.get(r, 0) + 1
                out[name][str(h)] = {
                    "guesses": n, "model_pct": pct(sum(1 for e in rows if e["right"]), n), "coin_pct": 50.0,
                    "repeat_last_pct": pct(sum(1 for e in rows if (e["f"]["r10"] > 0) == (e["move_pct"] > 0)), n),
                    "always_up_pct": pct(sum(1 for e in rows if e["move_pct"] > 0), n),
                    "sure_guesses": len(sure), "sure_pct": pct(sum(1 for e in sure if e["right"]), len(sure)),
                    "trades": len(trades), "trades_won": sum(1 for e in trades if e["trade"]["pnl"] > 0),
                    "pnl": round(sum(e["trade"]["pnl"] for e in trades), 2),
                    "pnl_gross": round(sum(e["trade"].get("pnl_gross", e["trade"]["pnl"]) for e in trades), 2),
                    "costs": round(sum(e["trade"]["costs"] for e in trades), 2),
                    "right_but_lost": right_dir_loss,
                    "itm_pnl": round(sum(itm), 2) if itm else None,
                    "loss_reasons": reasons,
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
            closed = not is_open(e)
            trades.append({"index": e["index"], "at": "%d:%02d" % divmod(e["at"], 60), "minutes": e["minutes"],
                           "option": "%s %d %s" % (e["index"], t["strike"], t["side"]), "expiry": t["expiry"],
                           "qty": t["qty"], "buy": t["buy"], "sell": t.get("sell"), "costs": t.get("costs"),
                           "pnl": t.get("pnl"), "index_move_pct": e.get("move_pct"), "open": not closed,
                           "now": t.get("now"), "now_ltp": t.get("now_ltp"), "now_at": t.get("now_at"),
                           "now_pnl": t.get("now_pnl"), "now_index": t.get("now_index"),
                           "exit": t.get("exit"), "exit_at": t.get("exit_at"), "why_lost": t.get("why_lost"),
                           "big": t.get("big"), "itm_pnl": (t.get("itm") or {}).get("pnl"), "path": t.get("path", [])[-60:]})
        live_pnl = [x["now_pnl"] for x in trades if x["open"] and x.get("now_pnl") is not None]
        skips = {}
        for e in self.log:
            if e.get("skip"):
                skips[e["skip"]] = skips.get(e["skip"], 0) + 1
        return {"guesses": [{"index": k, "price": price.get(k), "p": v} for k, v in latest.items()],
                "open_pnl": round(sum(live_pnl), 2) if live_pnl else None, "open_count": len(live_pnl),
                "trades": trades[-20:], "today": self.score(), "model_check": self.model.get("check"),
                "trade_on": sorted(TRADE_ON), "gate_why": self.gate_msg, "big_day": self.big.get("NIFTY"),
                "skipped": sorted([{"why": k, "count": v} for k, v in skips.items()], key=lambda x: -x["count"])[:5],
                "settings": {"sure": SURE, "stop": STOP, "target": TARGET, "max_trades": MAX_TRADES, "day_loss": DAY_LOSS,
                             "big_only": BIG_ONLY}}
