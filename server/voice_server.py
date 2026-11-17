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
print("✓ Model ready:", ckpt)

# 4. Server chalu karo (pehli baar 3–5 minute)
import secrets, urllib.request, urllib.error

PASSWORD = secrets.token_urlsafe(12)
PORT = 8080
server_log = open(WORK / "server.log", "w")
server = subprocess.Popen(
    [sys.executable, "tools/api_server.py",
     "--listen", f"0.0.0.0:{PORT}",
     "--llama-checkpoint-path", "checkpoints/s2-pro",
     "--decoder-checkpoint-path", "checkpoints/s2-pro/codec.pth",
     "--decoder-config-name", "modded_dac_vq",
     "--half",                      # T4 jaise GPU par bf16 nahi hota
     "--api-key", PASSWORD,
     "--max-text-length", "1000"],
    cwd=src, stdout=server_log, stderr=subprocess.STDOUT,
)

def healthy(base):
    req = urllib.request.Request(base + "/v1/health", headers={"Authorization": "Bearer " + PASSWORD})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status == 200
    except Exception:
        return False

def log_tail(n=40):
    return "".join(open(WORK / "server.log", errors="ignore").readlines()[-n:])

start = time.time()
while not healthy(f"http://127.0.0.1:{PORT}"):
    if server.poll() is not None:
        raise RuntimeError("Server ruk gaya. Ye galti thi:\n" + log_tail())
    if time.time() - start > 1500:
        raise RuntimeError("Server 25 minute mein chalu nahi hua:\n" + log_tail())
    time.sleep(10)
print(f"✓ Server chalu ({int(time.time() - start)} second)")

# 5. Internet ka link banao (Cloudflare, free, bina account) aur Sahayak wali line do
import re

cf = WORK / "cloudflared"
if not cf.exists():
    urllib.request.urlretrieve("https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64", cf)
    cf.chmod(0o755)

def open_tunnel():
    log_path = WORK / "tunnel.log"
    proc = subprocess.Popen([str(cf), "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{PORT}"],
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
    if checks % 15 == 0:
        print(time.strftime("%H:%M"), "✓ server chal raha hai:", f"SAHAYAK {URL} {PASSWORD}", flush=True)
