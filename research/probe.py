import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import study
study.get("https://www.nseindia.com/option-chain", 1)
for sym in ["NIFTY", "BANKNIFTY"]:
    b = study.get("https://www.nseindia.com/api/option-chain-contract-info?symbol=" + sym, 2)
    exp = json.loads(b)["expiryDates"][0]
    print("EXP", sym, json.loads(b)["expiryDates"][:4])
    c = study.get("https://www.nseindia.com/api/option-chain-v3?type=Indices&symbol=%s&expiry=%s" % (sym, exp), 2)
    print("CHAIN", sym, len(c) if c else None)
    if c:
        d = json.loads(c)
        print(list(d.keys()), list(d.get("records", {}).keys()) if isinstance(d.get("records"), dict) else "")
        recs = d.get("records", {}).get("data") or d.get("filtered", {}).get("data") or []
        print("n", len(recs), "underlying", d.get("records", {}).get("underlyingValue"))
        mid = recs[len(recs)//2] if recs else {}
        print(json.dumps(mid)[:1500])
