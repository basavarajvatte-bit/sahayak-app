# Sahayak 2.0 — Phase 1: Code audit aur architecture

Date: 23 Nov 2026. Yeh file har phase ke saath update hogi.

## 1. Abhi Sahayak kaise kaam karta hai

- **Awaaz aur baat:** `AssistService` (1156 lines) + `GeminiLive` (WebSocket). Dot dabao, bolo; Groq Whisper (`Whisper.kt`) awaaz ko shabd banata hai.
- **Phone chalana:** `PhoneControl` (1619 lines, Accessibility Service). Live ke paas 24 tools hain: `get_screen`, `tap_text`, `tap`, `tap_position`, `type_text`, `scroll`, `quick_action`, `schedule_work`, `ai_call` waghera.
- **Sochna aur jaanchna:** `Planner` — kaam se pehle plan, baad me review (DONE/FIX, max 3 baar), atakne par `recover`.
- **Yaad:** `Memory`, `Brain`, `Skills` (sikhaye kaam replay), `Ratings`, `History`, `DailyLearning`.
- **Background kaam:** `WorkList` (time par WhatsApp/email/reminder), `Watcher` (News Watch), TaskVox calls.
- **Suraksha:** payment, password, PIN, OTP aur bank/UPI app par ruk jaata hai. Keys repo me nahi.
- **Tests:** 20 unit test files (zyadatar Market, Clip, Voice logic). Assistant ke liye test nahi hain.

## 2. Asli dikkatein (code me mili)

| Dikkat | Kahan | Asar |
|---|---|---|
| Ek waqt me sirf ek kaam | `AssistService`: `currentOrder`, `stepLog`, `busyNow` sab ek hi global | Kaam chalte dot dabao to `stopEverything()` sab band kar deta hai |
| Ek hi AI company | `Planner`: sirf Gemini models (`gemini-flash-latest`, `2.5-flash`, `flash-lite`) | Gemini ki free limit khatam = sab ruk jaata hai |
| YouTube gaana nahi chalta | `dispatchOrder`: search ke baad Live ko bolta hai "first video tap karo" | Live kabhi tap nahi karta, wahi atak jaata hai |
| Research nahi | Sirf `searchNews` (Google Search grounding) | Lambi research, kai sources, report — nahi hota |
| Progress dikhta nahi | Sirf caption aur ghoomta dot | Kya chal raha hai pata nahi chalta |

## 3. Kya sach me ho sakta hai (seedhi baat)

- **Phone screen ek hai.** Android par ek time pe ek hi app saamne hota hai. Isliye do screen wale kaam (jaise YouTube + WhatsApp) ek saath nahi chal sakte. Woh **line (queue)** me chalenge.
  Lekin research, AI ka sochna, verify karna, calls, scheduled kaam — yeh sab **sach me ek saath** chal sakte hain, kyunki inhe screen nahi chahiye.
- **Playwright, Selenium, Appium, browser-use phone par nahi chalte.** Inhe computer/server chahiye. Phone par Chrome bhi Accessibility se hi chalega (jo pehle se hai). Server (Kaggle/Colab) wala raasta baad me optional rakhenge.
- **Ollama (local model) Realme 7 Pro par practical nahi.** RAM kam hai, bahut chhote model hi chalenge aur woh kamzor hain. Skip.
- **Coding Agent:** phone par code chalana safe/practical nahi. Abhi sirf script likhkar dikhana; chalana baad me server par.
- **Free models:** Gemini (key hai), Groq (key hai, abhi sirf Whisper ke liye), OpenRouter free models, Cerebras. Har ek ki free limit badalti rehti hai — app khud 429 (limit) pakad kar agle par jayega. Keys sirf app ki Settings me, kabhi chat ya repo me nahi.

## 4. Naya design

```
Awaaz (Gemini Live) ──► Orchestrator ──► TaskManager (kai Task ek saath)
                             │
             ┌───────────────┼────────────────┬──────────────┐
        ScreenWorker    ResearchWorkers   ThinkWorker     Verifier
      (ek, screen lock,  (kai, parallel,  (plan/likhna)  (alag model se
       queue me kaam)    web + grounding)                 jaanch)
             └───────────────┴──── ModelRouter ┴──────────────┘
                    (Gemini / Groq / OpenRouter / Cerebras adapters,
                     limit pakadna, cooldown, kaam ke hisaab se model)
```

- **Task:** har order ka apna `id`, status, subtasks, log, time. Globals hatenge.
- **Orchestrator:** order dekhkar decide karega — chhota (seedha, jaise abhi), screen kaam, research, ya mila-jula. Chhote order par koi extra agent nahi.
- **ScreenWorker:** screen ka ek "lock". Ek screen kaam chal raha ho to naya screen kaam line me lagega, band nahi hoga.
- **ResearchWorker:** 2–5 ek saath, alag sources/models. Har baat ke saath link; jo verify nahi hua woh alag likha jayega.
- **Verifier:** jis model ne kaam kiya, usse alag model se jaanch.
- **Live ke naye tools:** `start_task`, `task_status`, `cancel_task`, `change_task`. Isse kaam chalte-chalte bhi baat ho sakti hai.
- **Dot ka naya niyam:** chhota tap = sab roko (jaise abhi). Hold karke bolo = naya kaam line me / ek saath.
- **Dashboard:** naya screen — tasks, agents, model, chal rahe/ho gaye/fail subtasks, time, progress.

## 5. Phases (kaam ka kram)

Faayde ke hisaab se kram thoda badla hai: sabse pehle wahi jo aapko roz chubhta hai.

| Phase | Kya banega | Pehchaan ki ho gaya |
|---|---|---|
| 1 | Audit + yeh design | Yeh file ✅ |
| 2 | TaskManager + Orchestrator, kai task ek saath, screen queue | Kaam chalte naya order do, pehla band na ho |
| 3 | ModelRouter: Gemini + Groq + OpenRouter (+ Cerebras), limit par agla model | Gemini limit khatam ho to bhi jawab aaye |
| 4 | Parallel workers (research/think/verify ek saath) | Ek order par kai agent dashboard me ek saath dikhe |
| 5 | Android control pakka: YouTube play local tap se, popup hatana, retry ki limit, tarika fail ho to doosra (text → element → position) | "Pal Pal Dil Ke Paas chalao" gaana chalne tak kare |
| 6 | Web research agent: official site, PDF, cross-check, report + links | Faculty list jaisa kaam links ke saath |
| 7 | Memory: app/website ka raasta yaad, galtiyon ka record, search | Doosri baar wahi kaam kam steps me |
| 8 | Recovery loop: plan → karo → dekho → jaancho → sudharo (limit ke saath) | Ek step fail par poora kaam fail na ho |
| 9 | Dashboard + kaam chalte baat | Sab live dikhe |
| 10 | Speed, Settings me keys ka encrypted storage, tests | Assistant ke liye unit tests |

## 6. Har phase ke niyam

- Purane features nahi hatenge. Gemini Live aur phone control waise hi rahenge.
- Har phase ke baad APK build (GitHub Actions) aur aap phone par test karoge; tabhi agla phase.
- Jo bana aur test nahi hua, use "ho gaya" nahi kahenge.
