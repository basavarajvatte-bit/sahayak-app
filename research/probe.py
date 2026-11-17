import urllib.request, http.cookiejar, json
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
H = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36", "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9"}
def g(u):
    try:
        return op.open(urllib.request.Request(u, headers=H), timeout=30).read()
    except Exception as e:
        print("NO", u[:120], e); return None
for u in ["https://www.nseindia.com/api/corporates-corporateActions?index=equities&symbol=INFY&from_date=01-01-2016&to_date=18-11-2026",
          "https://www.nseindia.com/api/corporate-announcements?index=equities&symbol=INFY&from_date=01-01-2024&to_date=18-11-2026",
          "https://www.nseindia.com/api/corporate-board-meetings?index=equities&symbol=INFY&from_date=01-01-2020&to_date=18-11-2026",
          "https://www.nseindia.com/api/corporate-announcements?index=equities&symbol=INFY&from_date=01-01-2016&to_date=31-12-2016"]:
    b = g(u)
    if not b: continue
    try:
        d = json.loads(b)
    except Exception:
        print("notjson", u[:100], b[:200]); continue
    items = d if isinstance(d, list) else d.get("data", d)
    print("URL", u[:110], "count", len(items) if hasattr(items, "__len__") else "?")
    if isinstance(items, list):
        for it in items[:4]:
            print(json.dumps(it)[:600])
        if items and "caBroadcastDate" in items[0]:
            print("broadcast filled", sum(1 for x in items if x.get("caBroadcastDate")), "of", len(items))
