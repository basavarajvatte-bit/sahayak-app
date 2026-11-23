# NIFTY AI — Phase 1 audit (23 Nov 2026)

Sirf jaanch. Is phase me koi code nahi badla. Sirf seekhne ke liye, nivesh ki salah nahi.

## 1. Abhi system kaise kaam karta hai

| Hissa | File | Kya karta hai |
|---|---|---|
| Index model | `research/index_game.py` | Nifty aur Bank Nifty: har 10 minute agle 10 aur 30 minute upar/neeche ki sambhavna (p) |
| Shares model | `research/game.py` | Nifty 50 ke shares ka wahi andaza, dummy ₹10,000 trade |
| Chalane wala | `.github/workflows/game.yml` | GitHub Actions: subah 8:37 seekhna, 9:15–3:30 har 10 minute andaza |
| Pattern study | `research/index_study.py` (`index.yml`) | Har Sunday: ~18 saal ke din wale pattern. Yeh live andaza nahi deta |
| App screen | `GameActivity.kt` | `live.json` padhkar "Aaj ke andaze" aur dummy trade dikhata hai |

**Model:** simple logistic regression (khud likha hua), 10 cheezein (features):
`r10` (10 min ka badlav), `r30`, `rday` (open se), `range30`, `late` (din ka samay), `gap`, `prev` (kal ka badlav), `vix` (KAL ki VIX), `expiry`, `first15`.

**Data:** Yahoo Finance (5-minute candle, unofficial API) + NSE website ka option chain (unofficial API).

**Seekhna:** roz subah pichhle ~60 din ke 5-minute data se. Aakhri 10 din par imaandar jaanch (model ne woh din nahi dekhe), phir saare 60 din par final model.

**Dummy option trade:** p ≥ 56% to ATM Call, p ≤ 44% to ATM Put. Asli NSE **ask** par kharid, 10/30 min baad asli **bid** par bech. Brokerage, STT, exchange, GST, stamp sab kaata jaata hai. Yeh hissa achha aur imaandar bana hai.

**Jo NAHI hai:** din bhar ki direction ka live andaza, Bank Nifty–Nifty rishta, multi-timeframe, OI/PCR/Greeks analysis, breadth, global market, "andaza nahi" wala option, permanent prediction history. Pehle socha gaya "NIFTY AI" section (5 min se 3 ghante, Kaggle) bana hi nahi hai.

## 2. 64% kahan se aaya

App ka "Aaj ke andaze" = **sirf aaj** ke andaze me se kitne sahi. 23 Nov ko Nifty 10-minute: **14 me se 9 sahi = 64.3%**.

- Sirf 14 andaze, ek subah. Itne kam par 64% ka koi matlab nahi; sikka uchhalkar bhi aisa aa jaata hai.
- Usi waqt "pichhli chaal dohrao" wala bewakoof tareeka bhi 64.3% tha.
- Usi subah Bank Nifty 10-minute: **14 me se 2 = 14.3%**.

### Sab live din milakar (18–20 Nov + 23 Nov aadha din)

| | Andaze | Model sahi | "Hamesha upar" bolta to | "Pichhli chaal dohrao" |
|---|---|---|---|---|
| Nifty 10 min | 86 | 57.0% | 58.1% | 58.1% |
| Nifty 30 min | 78 | 67.9% | 70.5% | 52.6% |
| Bank Nifty 10 min | 86 | **36.0%** | 55.8% | 59.3% |
| Bank Nifty 30 min | 78 | **47.4%** | 66.7% | 57.7% |

### Model ki apni jaanch (6–20 Nov, model ne yeh din nahi dekhe)

| | Andaze | Model | Hamesha upar |
|---|---|---|---|
| Nifty 10 min | 330 | 52.7% | 53.0% |
| Nifty 30 min | 310 | 57.4% | 56.1% |
| Bank Nifty 10 min | 330 | 41.5% | 58.5% |
| Bank Nifty 30 min | 310 | 39.4% | 60.6% |

**Seedha nateeja:** asli accuracy 64% nahi hai. Nifty par model "hamesha upar bolo" se behtar nahi. Bank Nifty par sikke se bhi kharab, aur lagatar kharab. Itna lagatar galat hona ya to koi bug hai ya model ulti cheez seekh raha hai; isse pehle jaanchna hai.

Dummy trade: sirf 3–4 din, 20–25 trade. Kuch din munafa dikha, par itne kam trade se kuch sabit nahi hota.

## 3. Accuracy kam hone ke kaaran

1. **Data bahut kam aur chhota:** 60 din × ~35 andaze. 10 min ka bazaar zyadatar shor (noise) hai.
2. **30-minute andaze ek doosre par chadhe hue:** har 10 min par agle 30 min ka andaza, to 3 lagatar andaze ek hi chaal dekhte hain. Asli sample size dikhne se ~3 guna kam.
3. **Baseline se tulna screen par nahi:** app sirf "model %" dikhata hai, "hamesha upar" wala nahi. Bazaar upar chale to model achha dikhta hai.
4. **VIX kal ki:** feature me aaj ki live VIX nahi, kal ka close.
5. **Bank Nifty ke liye alag soch nahi:** wahi features, wahi tareeka. Bank Nifty ka expiry din bhi andaaze se.
6. **56% ki hadd jaanchi nahi gayi (calibration nahi):** model 60% bole to sach me 60% baar sahi hota hai ya nahi, yeh kabhi naapa nahi.
7. **"Andaza nahi" ka option nahi:** har 10 min zabardasti upar/neeche. Coverage aur accuracy alag nahi gine jaate.
8. **Permanent history nahi:** `index_today.jsonl` roz mit jaati hai. Sirf din ka total `history.json` me (abhi 3 din). Galti ki jaanch ke liye har andaza chahiye.
9. **Data source kamzor:** Yahoo aur NSE website ki API officially nahi milti. NSE kabhi block karta hai, Yahoo me deri ya candle gayab ho sakti hai. Data purana ya adhoora hone ki koi jaanch nahi.
10. **Option chain sirf bhaav ke liye:** OI, PCR, IV ka andaze me koi upyog nahi.

## 4. Kya sach me ho sakta hai

- **Pakka ho sakta hai:** imaandar score (baseline ke saath, sample size, din), permanent history, calibration, "andaza nahi" option, data ki jaanch, live VIX, OI/PCR/IV features, breadth (Nifty 50 ka data pehle se aata hai), walk-forward test, model versions + rollback.
- **Ho sakta hai, par fayda pakka nahi:** multi-timeframe, ML models (Random Forest/LightGBM). Har cheez tabhi rahegi jab test me sach me madad kare.
- **Seedhi baat:** 10–30 minute ka index andaza duniya ke bade funds ke liye bhi mushkil hai. Shuru me 52–55% asli accuracy bhi achhi maani jaati hai. 70–80% ka vaada nahi kar sakte.
- **Option ka faayda ≠ direction sahi:** theta, IV, spread ki wajah se sahi direction par bhi nuksaan ho sakta hai. Score me `right_but_lost` pehle se hai; ise screen par dikhana chahiye.

## 5. Data aur API

| Source | Kya milta hai | Kharcha | Note |
|---|---|---|---|
| Yahoo (abhi) | Index candle | Free | Unofficial, kabhi bhi band/deri |
| NSE website (abhi) | Option chain | Free | Unofficial, block hota hai |
| Angel One SmartAPI | Live + historical, WebSocket | Free (Angel One account chahiye) | Sabse sasta pakka raasta |
| Upstox API | Live feed, option chain + Greeks | Free (account chahiye) | Historical option chain nahi deta |
| Fyers API | Live + historical | Free (account chahiye) | |
| Dhan Data API | Live + historical | ~₹499/mahina | |
| Zerodha Kite Connect | Live + historical | Paid, mahine ki fees | |

Broker API ke liye us broker me demat account aur API key chahiye. Key sirf GitHub Secrets me jayegi, chat ya code me kabhi nahi. Purane option chain (OI history) ka free aur pakka source nahi mila; isliye ise abhi se roz khud save karna hoga.

## 6. Har phase me kya badlega

| Phase | Badlav | Pehchaan ki ho gaya |
|---|---|---|
| 2 Data | Har andaze ka permanent record; data purana/adhoora ho to "Live data unavailable"; live VIX; OI, PCR, IV, max pain, breadth; roz option chain save | Har andaza baad me dekh sakein; kharab data par andaza nahi |
| 3 Andaza | Bank Nifty bug/ulti seekh ki jaanch; calibration; UP / DOWN / UNCLEAR; multi-timeframe aur naye features sirf test pass hone par; LR vs Random Forest/LightGBM walk-forward | Naya model purane se 3+ hafte ke anjaan data par behtar, baseline se upar |
| 4 Seekhna | Galat andaze ki apne aap jaanch (sideways? VIX? ulte signal?); hafta/mahina report; model versions + rollback; bina test naya model nahi | Report me sabse kamzor halaat dikhe |
| 5 Screen | Abhi ka design rakhte hue: confidence, support/resistance, "UNCLEAR", baseline ke saath accuracy, sample size, data ka time, risk warning | Phone par saaf dikhe |
| 6 Test | Purana vs naya, walk-forward, kharche ke saath P&L, drawdown, Brier score | Number ke saath report |

## 7. Pehle kya karna chahiye (meri salah)

1. **Phase 2 ka pehla hissa:** permanent history + baseline ke saath imaandar score screen par. Iske bina koi sudhar naapa hi nahi ja sakta.
2. **Bank Nifty ki jaanch:** lagatar ulta kyun hai.
3. **"UNCLEAR" option** — sabse sasta aur sabse kaam ka sudhar.
4. **Angel One ya Upstox account** — tabhi pakka live data aur OI.

Approval ke bina koi bada badlav nahi hoga.
