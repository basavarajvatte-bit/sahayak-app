"""Nifty aur Bank Nifty ka pattern study (intraday ke kaam ka).

Din-wise data (~18 saal) aur pichhle 60 din ka 5-minute data. Har pattern ke liye:
kitne din, kitni baar index upar band hua (open se close), aur beech ka (median) move.
Nateeja: index_summary.md aur index_patterns.json. Sirf seekhne ke liye, nivesh ki salah nahi.
"""
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from statistics import median

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import study  # noqa: E402

IST = timezone(timedelta(hours=5, minutes=30))
INDEXES = [("Nifty 50", "^NSEI", "NIFTY 50", "NIFTYBEES.NS"), ("Bank Nifty", "^NSEBANK", "NIFTY BANK", "BANKBEES.NS")]
WEEKLY_SWITCH = "2025-09-01"   # is din se Nifty weekly expiry Thursday se Tuesday hui


def yahoo(symbol, rng, interval):
    for host in ("query1", "query2"):
        url = ("https://" + host + ".finance.yahoo.com/v8/finance/chart/" + urllib.request.quote(symbol)
               + "?range=" + rng + "&interval=" + interval + ("&crumb=" + urllib.request.quote(study.CRUMB[0]) if study.CRUMB[0] else ""))
        raw = study.get(url, 3)
        if not raw:
            continue
        try:
            res = json.loads(raw)["chart"]["result"][0]
            q = res["indicators"]["quote"][0]
            rows = []
            for t, o, h, l, c in zip(res["timestamp"], q["open"], q["high"], q["low"], q["close"]):
                if None in (o, h, l, c) or o <= 0:
                    continue
                d = datetime.fromtimestamp(t, IST)
                rows.append({"t": d, "day": d.strftime("%Y-%m-%d"), "o": o, "h": h, "l": l, "c": c})
            if rows:
                return rows
        except (KeyError, IndexError, TypeError, ValueError):
            continue
    return []


def nse_history(name, years=18):
    """Yahoo na de to NSE se, saal-saal karke."""
    rows = {}
    end = datetime.now(IST)
    study.get("https://www.nseindia.com/reports-indices-historical-index-data", 1)
    for k in range(years):
        to = end - timedelta(days=365 * k)
        fr = to - timedelta(days=364)
        url = ("https://www.nseindia.com/api/historical/indicesHistory?indexType=" + urllib.request.quote(name)
               + "&from=" + fr.strftime("%d-%m-%Y") + "&to=" + to.strftime("%d-%m-%Y"))
        raw = study.get(url, 2)
        time.sleep(1)
        if not raw:
            continue
        try:
            data = json.loads(raw)
        except ValueError:
            continue
        items = []
        if isinstance(data, dict):
            inner = data.get("data", data)
            if isinstance(inner, dict):
                for v in inner.values():
                    if isinstance(v, list):
                        items += v
            elif isinstance(inner, list):
                items = inner
        for it in items:
            if not isinstance(it, dict):
                continue
            pick = lambda word: next((it[k] for k in it if word in k.upper() and "INDEX" in k.upper()), None)  # noqa: E731
            try:
                day = datetime.strptime(str(it.get("EOD_TIMESTAMP") or it.get("HistoricalDate")), "%d-%b-%Y")
                o, h, l, c = (float(pick(w)) for w in ("OPEN", "HIGH", "LOW", "CLOSE"))
            except (TypeError, ValueError):
                continue
            rows[day.strftime("%Y-%m-%d")] = {"t": day, "day": day.strftime("%Y-%m-%d"), "o": o, "h": h, "l": l, "c": c}
    return [rows[d] for d in sorted(rows)]


def stat(days, field="oc"):
    vals = [d[field] for d in days if d.get(field) is not None]
    if not vals:
        return None
    return {"days": len(vals), "up_pct": round(sum(1 for v in vals if v > 0) * 100 / len(vals)),
            "median": round(median(vals), 2), "avg": round(sum(vals) / len(vals), 2)}


def range_stat(days):
    vals = [d["rng"] for d in days]
    if not vals:
        return None
    return {"days": len(vals), "median_range": round(median(vals), 2),
            "over_1pct": round(sum(1 for v in vals if v >= 1) * 100 / len(vals)),
            "over_2pct": round(sum(1 for v in vals if v >= 2) * 100 / len(vals))}


def is_expiry(day, name, all_days):
    """Weekly expiry ka din (andaaza): 2025-09 se pehle Thursday, uske baad Tuesday. Chhutti ho to pichhla din.
    Bank Nifty ka weekly 13-Nov-2024 tak (Thursday/Wednesday mix), uske baad sirf monthly, isliye Bank Nifty ke
    liye sirf 2016-2024 ke Thursday/Wednesday ko weekly mana."""
    d = datetime.strptime(day, "%Y-%m-%d")
    if name == "Nifty 50":
        target = 1 if day >= WEEKLY_SWITCH else 3
    else:
        if day > "2024-11-13" or day < "2016-06-01":
            return False
        target = 2 if day >= "2023-09-04" else 3
    # Is hafte ka target din, ya chhutti ho to usse pehle ka aakhri trading din
    delta = target - d.weekday()
    if delta < 0:
        return False
    want = (d + timedelta(days=delta)).strftime("%Y-%m-%d")
    later = [x for x in all_days if day < x <= want]
    if later or day > want:
        return False
    # Agar target din khud nahi hai, to hafta poora hona chahiye (warna aaj ka din galti se expiry ban jata)
    return day == want or any(x > want for x in all_days[-10:])


def daily_study(name, rows, vix):
    days = []
    for i, r in enumerate(rows):
        if i == 0:
            continue
        p = rows[i - 1]
        days.append({"day": r["day"], "wd": r["t"].weekday(),
                     "gap": (r["o"] / p["c"] - 1) * 100, "oc": (r["c"] / r["o"] - 1) * 100,
                     "cc": (r["c"] / p["c"] - 1) * 100, "rng": (r["h"] - r["l"]) / r["o"] * 100,
                     "prev_cc": days[-1]["cc"] if days else None, "vix": vix.get(p["day"])})
    all_days = [d["day"] for d in days]
    for d in days:
        d["expiry"] = is_expiry(d["day"], name, all_days)
    for i, d in enumerate(days):
        d["next_oc"] = days[i + 1]["oc"] if i + 1 < len(days) else None
        d["next_cc"] = days[i + 1]["cc"] if i + 1 < len(days) else None
        run = 0
        for j in range(i, max(-1, i - 10), -1):
            if days[j]["cc"] > 0 and run >= 0:
                run += 1
            elif days[j]["cc"] < 0 and run <= 0:
                run -= 1
            else:
                break
        d["run"] = run

    out = {"from": days[0]["day"], "to": days[-1]["day"], "groups": {}}
    g = out["groups"]
    g["All days (open to close)"] = stat(days)
    g["Gap up 0.5%+ : open to close"] = stat([d for d in days if d["gap"] >= 0.5])
    g["Gap down 0.5%+ : open to close"] = stat([d for d in days if d["gap"] <= -0.5])
    g["Gap up 1%+ : open to close"] = stat([d for d in days if d["gap"] >= 1])
    g["Gap down 1%+ : open to close"] = stat([d for d in days if d["gap"] <= -1])
    g["Flat open (within 0.2%) : open to close"] = stat([d for d in days if abs(d["gap"]) < 0.2])
    g["Day after fall of 1.5%+ : open to close"] = stat([dict(d, oc=d["next_oc"]) for d in days if d["cc"] <= -1.5])
    g["Day after rise of 1.5%+ : open to close"] = stat([dict(d, oc=d["next_oc"]) for d in days if d["cc"] >= 1.5])
    g["After 3+ up days in a row : next day close to close"] = stat([dict(d, oc=d["next_cc"]) for d in days if d["run"] >= 3])
    g["After 3+ down days in a row : next day close to close"] = stat([dict(d, oc=d["next_cc"]) for d in days if d["run"] <= -3])
    for wd, label in enumerate(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]):
        g[label + " : open to close"] = stat([d for d in days if d["wd"] == wd])
    g["Weekly expiry day : open to close"] = stat([d for d in days if d["expiry"]])
    g["Not expiry day : open to close"] = stat([d for d in days if not d["expiry"]])

    r = out["ranges"] = {}
    r["All days"] = range_stat(days)
    r["Weekly expiry day"] = range_stat([d for d in days if d["expiry"]])
    r["Not expiry day"] = range_stat([d for d in days if not d["expiry"]])
    for lo, hi, label in [(0, 12, "VIX under 12"), (12, 16, "VIX 12 to 16"), (16, 20, "VIX 16 to 20"), (20, 30, "VIX 20 to 30"), (30, 999, "VIX 30+")]:
        r[label + " (day before)"] = range_stat([d for d in days if d["vix"] is not None and lo <= d["vix"] < hi])
    last5 = [d for d in days if d["day"] >= (datetime.now(IST) - timedelta(days=365 * 5)).strftime("%Y-%m-%d")]
    r["Last 5 years"] = range_stat(last5)
    return out


def intraday_study(bars):
    """60 din ka 5-minute data: pehle 15 minute ki chaal ke baad baaki din kya hua, aur kis ghante sabse zyada hilchal."""
    byday = {}
    for b in bars:
        m = b["t"].hour * 60 + b["t"].minute
        if 9 * 60 + 15 <= m <= 15 * 60 + 25:
            byday.setdefault(b["day"], []).append((m, b))
    first = []
    hours = {}
    for day, lst in sorted(byday.items()):
        lst.sort(key=lambda x: x[0])
        if len(lst) < 60:
            continue
        o = lst[0][1]["o"]
        c15 = next((b["c"] for m, b in lst if m >= 9 * 60 + 25), None)
        close = lst[-1][1]["c"]
        if c15:
            first.append({"first15": (c15 / o - 1) * 100, "rest": (close / c15 - 1) * 100})
        for m, b in lst:
            h = m // 60
            hours.setdefault(h, []).append(abs(b["c"] / b["o"] - 1) * 100)
    res = {"days": len(first)}
    up = [f for f in first if f["first15"] > 0.15]
    dn = [f for f in first if f["first15"] < -0.15]
    res["First 15 min up 0.15%+ : rest of day went up"] = {"days": len(up), "pct": round(sum(1 for f in up if f["rest"] > 0) * 100 / len(up)) if up else None}
    res["First 15 min down 0.15%+ : rest of day went down"] = {"days": len(dn), "pct": round(sum(1 for f in dn if f["rest"] < 0) * 100 / len(dn)) if dn else None}
    res["Avg 5-min move by hour (%)"] = {"%d:00" % h: round(sum(v) / len(v), 3) for h, v in sorted(hours.items())}
    return res


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    study.yahoo_login()
    vixrows = yahoo("^INDIAVIX", "max", "1d")
    vix = {r["day"]: r["c"] for r in vixrows}
    print("vix days", len(vix), flush=True)
    result = {"made": datetime.now(IST).strftime("%Y-%m-%d"), "indexes": {}}
    md = ["# Nifty and Bank Nifty patterns\n",
          "Learning only, not investment advice. Up % = how often the index closed above where it opened (50% = coin toss).",
          "Median = middle move in %. Expiry days are estimated (Thursday weekly until Aug 2025, Tuesday after; Bank Nifty weekly only until Nov 2024).\n"]
    for name, ysym, nse_name, etf in INDEXES:
        rows = yahoo(ysym, "max", "1d")
        source = "Yahoo " + ysym
        if len(rows) < 1000:
            rows = nse_history(nse_name)
            source = "NSE"
        print(name, source, len(rows), flush=True)
        if len(rows) < 500:
            md.append(f"## {name}\n\nCould not get enough data.\n")
            continue
        daily = daily_study(name, rows, vix)
        bars = yahoo(ysym, "60d", "5m")
        if len(bars) < 500:
            bars = yahoo(etf, "60d", "5m")
        intra = intraday_study(bars) if bars else {}
        result["indexes"][name] = {"source": source, "daily": daily, "intraday": intra}
        md.append(f"## {name} ({daily['from']} to {daily['to']}, {source})\n")
        md += ["| Pattern | Days | Up % | Median move |", "|---|---|---|---|"]
        for label, s in daily["groups"].items():
            if s:
                md.append(f"| {label} | {s['days']} | {s['up_pct']}% | {s['median']:+.2f}% |")
        md += ["", "| Day range (high to low) | Days | Median range | 1%+ days | 2%+ days |", "|---|---|---|---|---|"]
        for label, s in daily["ranges"].items():
            if s:
                md.append(f"| {label} | {s['days']} | {s['median_range']:.2f}% | {s['over_1pct']}% | {s['over_2pct']}% |")
        if intra:
            md += ["", f"Last 60 days, 5-minute data ({intra['days']} days):", ""]
            for label, v in intra.items():
                if isinstance(v, dict) and "pct" in v:
                    md.append(f"- {label}: {v['pct']}% of {v['days']} days")
            hrs = intra.get("Avg 5-min move by hour (%)", {})
            if hrs:
                md.append("- Average size of a 5-minute move by hour: " + ", ".join(f"{h} {v}%" for h, v in hrs.items()))
        md.append("")
    with open(os.path.join(out_dir, "index_patterns.json"), "w") as f:
        json.dump(result, f, indent=1)
    with open(os.path.join(out_dir, "index_summary.md"), "w") as f:
        f.write("\n".join(md))
    print("\n".join(md))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out")
