"""Sahayak voice server: Fish Speech S2-Pro + Cloudflare link. Kaggle cell mein ek line se chalao:
import urllib.request; exec(urllib.request.urlopen("https://raw.githubusercontent.com/basavarajvatte-bit/sahayak-app/main/server/voice_server.py").read())
"""


# 1. GPU hai ya nahi, aur kaam ki jagah
import os, subprocess, sys, time
from pathlib import Path

FISH_COMMIT = "0f8a12e8a651062fdee9c33a894bdccd3a8513f1"  # Fish Speech ka jaancha hua version
WORK = Path("/tmp/sahayak")
WORK.mkdir(parents=True, exist_ok=True)

def run(cmd, **kw):
    print("$", " ".join(cmd), flush=True)
    subprocess.run(cmd, check=True, **kw)

gpu = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"], capture_output=True, text=True)
if gpu.returncode != 0:
    raise RuntimeError("GPU nahi mila. Settings → Accelerator → GPU T4 x2 chuno, phir dobara chalao.")
print("GPU:", gpu.stdout.strip())

# 2. Fish Speech install (5–10 minute)
if subprocess.run(["which", "apt-get"], capture_output=True).returncode == 0:
    subprocess.run("apt-get -qq update && apt-get -qq install -y portaudio19-dev libsox-dev ffmpeg > /dev/null", shell=True)
src = WORK / "fish-speech"
if not src.exists():
    run(["git", "clone", "-q", "https://github.com/fishaudio/fish-speech.git", str(src)])
run(["git", "-C", str(src), "checkout", "-q", FISH_COMMIT])
run([sys.executable, "-m", "pip", "install", "-q", "--extra-index-url", "https://download.pytorch.org/whl/cu126", "-e", ".[cu126]"], cwd=src)
run([sys.executable, "-m", "pip", "install", "-q", "-U", "huggingface_hub[cli]"])
print("✓ Install ho gaya")

# 2b. Voice changer (Seed-VC) apne alag Python mein, peeche chalu karo. Ye Fish ke saath-saath taiyaar hota hai.
import secrets
PASSWORD = secrets.token_urlsafe(12)
VC_PORT = 8081
SEED_VC_COMMIT = "51383efd921027683c89e5348211d93ff12ac2a8"
VC_SCRIPT = "https://raw.githubusercontent.com/basavarajvatte-bit/sahayak-app/main/server/vc_server.py"
vc_setup = f"""
set -e
cd {WORK}
{sys.executable} -m pip install -q uv
[ -d seed-vc ] || git clone -q https://github.com/Plachtaa/seed-vc.git seed-vc
git -C seed-vc checkout -q {SEED_VC_COMMIT}
[ -x vcenv/bin/python ] || {sys.executable} -m uv venv -q -p 3.10 vcenv
{sys.executable} -m uv pip install -q -p vcenv/bin/python torch==2.4.0 torchaudio==2.4.0 --index-url https://download.pytorch.org/whl/cu121
{sys.executable} -m uv pip install -q -p vcenv/bin/python scipy==1.13.1 librosa==0.10.2 "huggingface-hub>=0.28.1,<1.0" munch==4.0.0 einops==0.8.0 descript-audio-codec==1.0.0 pydub==0.25.1 transformers==4.46.3 soundfile==0.12.1 numpy==1.26.4 pyyaml onnxruntime
curl -sSL -o seed-vc/vc_server.py {VC_SCRIPT}
echo "✓ Voice changer install ho gaya"
cd seed-vc && exec ../vcenv/bin/python vc_server.py {VC_PORT} {PASSWORD}
"""
vc_proc = subprocess.Popen(["bash", "-c", vc_setup], stdout=open(WORK / "vc.log", "w"), stderr=subprocess.STDOUT)
print("Voice changer peeche taiyaar ho raha hai", flush=True)


# 3. S2-Pro model download (~9 GB, 3–8 minute)
token = None
try:  # Kaggle: Add-ons → Secrets → HF_TOKEN (sirf zaroorat ho to)
    from kaggle_secrets import UserSecretsClient
    token = UserSecretsClient().get_secret("HF_TOKEN")
except Exception:
    pass
if not token:
    try:  # Colab ya doosri jagah
        from google.colab import userdata
        token = userdata.get("HF_TOKEN")
    except Exception:
        token = os.environ.get("HF_TOKEN")

ckpt = src / "checkpoints" / "s2-pro"
if not (ckpt / "codec.pth").exists():
    env = dict(os.environ)
    if token:
        env["HF_TOKEN"] = token
    from huggingface_hub import snapshot_download
    try:
        snapshot_download("fishaudio/s2-pro", local_dir=str(ckpt), token=token or None)
    except Exception as e:
        raise RuntimeError(
            "Model download nahi hua: " + str(e)[:300] +
            "\nAgar 'gated' ya '401' likha hai: huggingface.co/fishaudio/s2-pro par login karke license accept karo, "
            "phir huggingface.co → Settings → Access Tokens se Read token banao aur use is notebook ke Secrets mein HF_TOKEN naam se daalo. "
            "Token kabhi chat mein mat bhejna."
        )
# T4 mein 15 GB hai. Model apni yaad (KV cache) 32k lambi banata hai, jo GPU bhar deti hai.
# 6144 kaafi hai: 90 second ki recording + 500 akshar.
import json as _json
cfg_path = ckpt / "config.json"
cfg = _json.loads(cfg_path.read_text())
def _shrink(d):
    if isinstance(d, dict):
        for k, v in d.items():
            if k == "max_seq_len" and isinstance(v, int) and v > 6144:
                d[k] = 6144
            else:
                _shrink(v)
_shrink(cfg)
cfg_path.write_text(_json.dumps(cfg, indent=2))
print("✓ Model ready:", ckpt)

# 3b. Fish Speech mein chhote sudhaar (T4 ke liye)
def _patch(rel, old, new):
    p = src / rel
    s = p.read_text()
    if new in s:
        return
    if old not in s:
        print("⚠ patch nahi laga:", rel, flush=True)
        return
    p.write_text(s.replace(old, new, 1))

# Galti ho to asli wajah app tak jaaye
_patch("tools/server/views.py",
       'HTTPStatus.INTERNAL_SERVER_ERROR, content="Failed to generate speech"',
       'HTTPStatus.INTERNAL_SERVER_ERROR, content=("Failed to generate speech: " + type(e).__name__ + ": " + str(e))[:300]')
# Recording seedha soundfile se padho (torchaudio ke backend par bharosa nahi)
_patch("fish_speech/inference_engine/reference_loader.py",
       "waveform, original_sr = torchaudio.load(reference_audio, backend=self.backend)",
       "import soundfile as _sf\n        _data, original_sr = _sf.read(reference_audio, dtype='float32', always_2d=True)\n        waveform = torch.from_numpy(_data.T.copy())")
# Do GPU hon to awaaz wala hissa (codec) doosre GPU par, taaki pehle GPU par model ko poori jagah mile
_patch("tools/server/model_manager.py",
       "decoder_config_name, decoder_checkpoint_path, self.device",
       "decoder_config_name, decoder_checkpoint_path, ('cuda:1' if self.model_family != 'drama3' and torch.cuda.device_count() > 1 else self.device)")
_patch("fish_speech/inference_engine/__init__.py",
       "            device=self.decoder_model.device,\n            text=req.text,",
       "            device=(torch.device('cuda:0') if self.decoder_model.device.type == 'cuda' else self.decoder_model.device),\n            text=req.text,")
print("✓ Sudhaar lag gaye", flush=True)


# 4. Server chalu karo (pehli baar 5–10 minute)
import urllib.request, urllib.error

PORT = 8080

def healthy(base):
    req = urllib.request.Request(base + "/v1/health", headers={"Authorization": "Bearer " + PASSWORD})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status == 200
    except Exception:
        return False

def log_tail(n=40):
    lines = open(WORK / "server.log", errors="ignore").readlines()
    errors = [l for l in lines if "Error" in l or "error:" in l][-5:]
    return "ASLI GALTI:\n" + "".join(errors) + "\n--- poora log (aakhri hissa) ---\n" + "".join(lines[-n:])

def start_server(fast):
    """fast=True: torch.compile se awaaz kai guna tez banti hai, par chalu hone mein zyada time lagta hai."""
    global server
    cmd = [sys.executable, "tools/api_server.py",
           "--listen", f"0.0.0.0:{PORT}",
           "--llama-checkpoint-path", "checkpoints/s2-pro",
           "--decoder-checkpoint-path", "checkpoints/s2-pro/codec.pth",
           "--decoder-config-name", "modded_dac_vq",
           "--half",                      # T4 jaise GPU par bf16 nahi hota
           "--api-key", PASSWORD,
           "--max-text-length", "1000"]
    if fast:
        cmd.append("--compile")
    server = subprocess.Popen(cmd, cwd=src, stdout=open(WORK / "server.log", "w"), stderr=subprocess.STDOUT,
                              env={**os.environ, "PYTORCH_CUDA_ALLOC_CONF": "expandable_segments:True"})
    start = time.time()
    while not healthy(f"http://127.0.0.1:{PORT}"):
        if server.poll() is not None:
            return "Server ruk gaya. Ye galti thi:\n" + log_tail()
        if time.time() - start > 1800:
            server.kill()
            return "Server 30 minute mein chalu nahi hua:\n" + log_tail()
        time.sleep(10)
    print(f"✓ Server chalu ({'tez mode' if fast else 'saadha mode'}, {int(time.time() - start)} second)", flush=True)
    return ""

problem = start_server(fast=True)
if problem:
    print("Tez mode nahi chala, saadhe mode mein chala raha hoon.\n" + problem[-1500:], flush=True)
    problem = start_server(fast=False)
if problem:
    raise RuntimeError(problem)


# 4b. Ek hi darwaza: /v1/convert voice changer ko, baaki sab Fish ko
import http.client, http.server, threading

FRONT = 8090
VC_PATHS = ("/v1/convert", "/v1/vc_health")

class Front(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _forward(self):
        port = VC_PORT if self.path.startswith(VC_PATHS) else PORT
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection", "content-length", "transfer-encoding")}
        try:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=900)
            conn.request(self.command, self.path, body=body, headers=headers)
            r = conn.getresponse()
            status, data, out_headers = r.status, r.read(), r.getheaders()
            conn.close()
        except Exception as e:
            status, out_headers = 503, [("Content-Type", "text/plain; charset=utf-8")]
            data = (("Voice changer is still starting, try again in a few minutes" if port == VC_PORT else "Server is busy") + ": " + str(e)[:150]).encode()
        self.send_response(status)
        for k, v in out_headers:
            if k.lower() not in ("transfer-encoding", "connection", "content-length"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = _forward
    do_POST = _forward

threading.Thread(target=http.server.ThreadingHTTPServer(("127.0.0.1", FRONT), Front).serve_forever, daemon=True).start()
print("✓ Darwaza khula", flush=True)


# 5. Internet ka link banao (Cloudflare, free, bina account) aur Sahayak wali line do
import re

cf = WORK / "cloudflared"
if not cf.exists():
    urllib.request.urlretrieve("https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64", cf)
    cf.chmod(0o755)

def open_tunnel():
    log_path = WORK / "tunnel.log"
    proc = subprocess.Popen([str(cf), "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{FRONT}"],
                            stdout=open(log_path, "w"), stderr=subprocess.STDOUT)
    for _ in range(90):
        time.sleep(2)
        found = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", open(log_path, errors="ignore").read())
        if found:
            url = found.group(0)
            for _ in range(30):  # link duniya bhar mein failne mein kuch second lagte hain
                if healthy(url):
                    return proc, url
                time.sleep(4)
            break
    proc.kill()
    return None, None

tunnel, URL = open_tunnel()
if not URL:
    raise RuntimeError("Link nahi bana. Internet On hai? Ye notebook dobara chalao.")

line = f"SAHAYAK {URL} {PASSWORD}"
print("=" * 70)
print("Ye PURI line copy karke Sahayak app → My voice → My own server mein paste karo:\n")
print(line)
print("\nIs line mein password hai. Kisi ko mat bhejna, chat mein bhi nahi.")
print("=" * 70, flush=True)

# 6. Server ko chalta rakho. Ye cell chalta rahega; rok doge to server band.
checks = 0
seen = 0
vc_seen = 0
vc_dead = False
while True:
    time.sleep(60)
    checks += 1
    if server.poll() is not None:
        print("Server ruk gaya:\n" + log_tail())
        break
    if tunnel.poll() is not None:
        print("Link toot gaya, naya bana raha hoon…", flush=True)
        tunnel, URL = open_tunnel()
        if not URL:
            print("Naya link nahi bana. Notebook dobara chalao.")
            break
        print("NAYA line (purani kaam nahi karegi):\n" + f"SAHAYAK {URL} {PASSWORD}", flush=True)
    # Server ki galtiyan yahan bhi dikhao (Kaggle Logs mein)
    try:
        with open(WORK / "server.log", errors="ignore") as f:
            f.seek(seen)
            new = f.read()
            seen = f.tell()
        for l in new.splitlines():
            if "ERROR" in l or "Error" in l or "error:" in l:
                print("SERVER GALTI:", l[:400], flush=True)
    except OSError:
        pass
    # Voice changer ki khabar
    try:
        with open(WORK / "vc.log", errors="ignore") as f:
            f.seek(vc_seen)
            new = f.read()
            vc_seen = f.tell()
        for l in new.splitlines():
            if "✓" in l or "Error" in l or "error:" in l or "Traceback" in l:
                print("VOICE CHANGER:", l[:400], flush=True)
    except OSError:
        pass
    if vc_proc.poll() is not None and not vc_dead:
        vc_dead = True
        print("VOICE CHANGER ruk gaya (Fish wali awaaz phir bhi chalegi). Aakhri log:\n" + "".join(open(WORK / "vc.log", errors="ignore").readlines()[-25:]), flush=True)
    if checks % 15 == 0:
        print(time.strftime("%H:%M"), "✓ server chal raha hai:", f"SAHAYAK {URL} {PASSWORD}", flush=True)
