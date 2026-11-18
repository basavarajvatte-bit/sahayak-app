import sys, os, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import study
study.get("https://www.nseindia.com/option-chain", 1)
for u in ["https://www.nseindia.com/api/option-chain-indices?symbol=NIFTY",
          "https://www.nseindia.com/api/option-chain-contract-info?symbol=NIFTY",
          "https://www.nseindia.com/api/option-chain-v3?type=Indices&symbol=NIFTY",
          "https://www.nseindia.com/api/allIndices"]:
    b = study.get(u, 2)
    print("URL", u, len(b) if b else None)
    if b:
        print(b[:700])
y = study.get("https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=1d&interval=1m", 2)
print("yahoo nsei 1m", len(y) if y else None)
y = study.get("https://query1.finance.yahoo.com/v8/finance/chart/%5EINDIAVIX?range=1d&interval=1m", 2)
print("yahoo vix 1m", len(y) if y else None, y[:200] if y else "")
