import urllib.request, http.cookiejar, sys
cj = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
H = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36", "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9"}
def t(u):
    try:
        r = op.open(urllib.request.Request(u, headers=H), timeout=25); b = r.read(); print("OK", r.status, len(b), u[:100], b[:120])
    except Exception as e:
        print("NO", u[:100], e)
for u in ["https://fc.yahoo.com", "https://query2.finance.yahoo.com/v1/test/getcrumb",
          "https://query2.finance.yahoo.com/v8/finance/chart/TCS.NS?range=10y&interval=1d&events=div%2Csplit",
          "https://query1.finance.yahoo.com/v8/finance/chart/TCS.NS?range=1mo&interval=1d",
          "https://stooq.com/q/d/l/?s=tcs.in&i=d", "https://stooq.com/q/d/l/?s=^nsei&i=d",
          "https://www.nseindia.com", "https://www.nseindia.com/api/corporates-corporateActions?index=equities&symbol=TCS",
          "https://archives.nseindia.com/content/equities/EQUITY_L.csv",
          "https://niftyindices.com/IndexConstituent/ind_nifty500list.csv",
          "https://api.bseindia.com/BseIndiaAPI/api/DefaultData/w?Fdate=20200101&Purposecode=&TDate=20200131&ddlcategorys=E&ddlindustrys=&scripcode=&segment=0&strSearch=S",
          "https://www.screener.in/company/TCS/", "https://data.alpaca.markets", "https://finance.google.com"]:
    t(u)
