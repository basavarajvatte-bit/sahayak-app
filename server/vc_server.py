"""Sahayak voice changer: aapki boli hui baat (rukna, zor, lehja) wahi rahe, sirf awaaz chuni hui awaaz ki ho jaaye.

Seed-VC (zero-shot voice conversion) apne alag Python mein chalta hai. voice_server.py ise khud chalata hai:
    cd seed-vc && vcenv/bin/python vc_server.py PORT PASSWORD

POST /v1/convert  JSON {"source": base64 wav, "target": base64 wav, "steps": 25, "pitch": 0}
    -> mp3 (source ki baat, target ki awaaz)
GET  /v1/vc_health -> "ok" jab model taiyaar ho
"""
import base64
import io
import json
import sys
import tempfile
import threading
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import torch

PORT = int(sys.argv[1])
PASSWORD = sys.argv[2]
MAX_BODY = 40 * 1024 * 1024

device = torch.device("cuda:1" if torch.cuda.device_count() > 1 else ("cuda:0" if torch.cuda.is_available() else "cpu"))
print("Voice changer: model load ho raha hai on", device, flush=True)
from seed_vc_wrapper import SeedVCWrapper  # noqa: E402

vc = SeedVCWrapper(device=device)
lock = threading.Lock()  # GPU par ek baar mein ek hi kaam
print("✓ Voice changer taiyaar", flush=True)


def to_mp3(audio, sr):
    from pydub import AudioSegment
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
    seg = AudioSegment(pcm.tobytes(), frame_rate=sr, sample_width=2, channels=1)
    out = io.BytesIO()
    seg.export(out, format="mp3", bitrate="192k")
    return out.getvalue()


def convert(source_wav, target_wav, steps, pitch):
    with tempfile.NamedTemporaryFile(suffix=".wav") as s, tempfile.NamedTemporaryFile(suffix=".wav") as t:
        s.write(source_wav)
        s.flush()
        t.write(target_wav)
        t.flush()
        with lock, torch.no_grad():
            gen = vc.convert_voice(
                s.name, t.name,
                diffusion_steps=steps, length_adjust=1.0, inference_cfg_rate=0.7,
                # f0: aapki boli ka utaar-chadhaav (zor, sawaal, khushi) wahi rahe, bas uska level chuni awaaz jaisa
                f0_condition=True, auto_f0_adjust=True, pitch_shift=pitch,
                stream_output=False,
            )
            try:
                next(gen)
                audio = None
            except StopIteration as done:
                audio = done.value
    if audio is None or len(audio) == 0:
        raise RuntimeError("no audio came out")
    return to_mp3(np.asarray(audio, dtype=np.float32), 44100)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, body, kind="text/plain; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _allowed(self):
        return self.headers.get("Authorization", "") == "Bearer " + PASSWORD

    def do_GET(self):
        if not self._allowed():
            return self._send(401, "wrong password")
        if self.path.startswith("/v1/vc_health"):
            return self._send(200, "ok")
        self._send(404, "not found")

    def do_POST(self):
        if not self._allowed():
            return self._send(401, "wrong password")
        if not self.path.startswith("/v1/convert"):
            return self._send(404, "not found")
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > MAX_BODY:
                return self._send(413, "audio too big")
            data = json.loads(self.rfile.read(size))
            source = base64.b64decode(data["source"])
            target = base64.b64decode(data["target"])
            steps = max(4, min(50, int(data.get("steps", 25))))
            pitch = max(-12, min(12, int(data.get("pitch", 0))))
            mp3 = convert(source, target, steps, pitch)
            self._send(200, mp3, "audio/mpeg")
        except (KeyError, ValueError) as e:
            self._send(400, "bad request: " + str(e)[:200])
        except Exception as e:  # GPU ya model ki galti: wajah app tak jaaye
            traceback.print_exc()
            sys.stdout.flush()
            self._send(500, ("Voice change failed: " + type(e).__name__ + ": " + str(e))[:300])


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
