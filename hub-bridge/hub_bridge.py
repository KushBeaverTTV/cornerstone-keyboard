#!/usr/bin/env python3
"""Cornerstone Hub Bridge: exposes the keyboard's Agent Hub contract and
forwards every request to the Hermes agent running on this machine.

Keyboard contract (see java/.../uix/actions/HubRepository.kt):
  POST {base}/agentkey   {"text": str, "agent": str}  + Bearer HUB_TOKEN  -> {"answer": str}
  GET  {base}/agents                                                       -> {"agents": {id: {name, role, online}}}
  POST {base}/crash      {"trace": str}                + Bearer HUB_TOKEN  -> 204
  GET  {base}/health                                                       -> {"service", "status", "hermes"}

Hermes side: the OpenAI-compatible API server a `hermes gateway` runs
(POST /v1/chat/completions, Bearer API_SERVER_KEY). Kaori is the Hermes
"kaori" profile, whose gateway serves 127.0.0.1:8643; the default profile's
gateway serves 8642. HERMES_URL / HERMES_ENV_FILE in the env file pick one.

Standard library only, so it runs on the portable CPython under
C:\\Cornerstone\\tools\\python with no installs. Configuration comes from an
env file (default: C:\\Cornerstone\\Cornerstone_Secrets\\hub-bridge.env) plus an
optional agents JSON next to this script (hub-bridge.agents.json).
"""

from __future__ import annotations

import argparse
import json
import logging
import logging.handlers
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SERVICE_NAME = "cornerstone-hub-bridge"
DEFAULT_ENV_FILE = r"C:\Cornerstone\Cornerstone_Secrets\hub-bridge.env"
DEFAULT_HERMES_ENV = os.path.join(os.environ.get("LOCALAPPDATA", ""), "hermes", "profiles", "kaori", ".env")
DEFAULT_LOG_DIR = os.path.join(os.environ.get("LOCALAPPDATA", "."), "CornerstoneHubBridge")

# The keyboard inserts the answer straight into a text field, so the default
# persona asks for insertable plain text. Per-agent overrides live in
# hub-bridge.agents.json.
DEFAULT_SYSTEM_PROMPT = (
    "You are answering from inside a phone keyboard (Cornerstone Keyboard). "
    "Your reply is inserted directly into the text field the user is typing in. "
    "Reply with only the text to insert: no preamble, no explanations, no markdown "
    "fences, no sign-off. Be concise. If the request is a rewrite, return only the "
    "rewritten text."
)

DEFAULT_AGENTS = {
    "kaori": {"name": "Kaori", "role": "Personal presence agent (Hermes, on the PC)"},
}

log = logging.getLogger(SERVICE_NAME)


# --------------------------------------------------------------------------- config
def load_env_file(path: str) -> dict[str, str]:
    """Minimal KEY=VALUE parser (no interpolation). Missing file -> {}."""
    values: dict[str, str] = {}
    p = Path(path)
    if not p.is_file():
        return values
    for raw in p.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key.strip()] = value
    return values


class Config:
    def __init__(self, env_file: str, agents_file: str | None):
        env = load_env_file(env_file)
        get = lambda k, d="": os.environ.get(k) or env.get(k, d)  # noqa: E731

        self.bind = get("HUB_BIND", "0.0.0.0")
        self.port = int(get("HUB_PORT", "8899"))
        self.token = get("HUB_TOKEN", "")
        self.hermes_url = get("HERMES_URL", "http://127.0.0.1:8643").rstrip("/")
        self.hermes_model = get("HERMES_MODEL", "")  # blank = Hermes' virtual default
        self.hermes_timeout = float(get("HERMES_TIMEOUT_SECONDS", "180"))
        self.log_dir = get("HUB_LOG_DIR", DEFAULT_LOG_DIR)

        # Hermes API key: explicit override, else read straight from Hermes' own .env
        # so there is exactly one place it lives.
        self.hermes_api_key = get("HERMES_API_KEY", "")
        if not self.hermes_api_key:
            hermes_env = load_env_file(get("HERMES_ENV_FILE", DEFAULT_HERMES_ENV))
            self.hermes_api_key = hermes_env.get("API_SERVER_KEY", "")

        self.agents: dict[str, dict] = dict(DEFAULT_AGENTS)
        self.system_prompt = DEFAULT_SYSTEM_PROMPT
        if agents_file and Path(agents_file).is_file():
            data = json.loads(Path(agents_file).read_text(encoding="utf-8"))
            if isinstance(data.get("system_prompt"), str):
                self.system_prompt = data["system_prompt"]
            if isinstance(data.get("agents"), dict):
                self.agents = {str(k): dict(v) for k, v in data["agents"].items()}
        if not self.agents:
            self.agents = dict(DEFAULT_AGENTS)
        self.default_agent = next(iter(self.agents))

    def validate(self) -> list[str]:
        problems = []
        if not self.token:
            problems.append("HUB_TOKEN is empty: refusing to start an unauthenticated bridge")
        if not self.hermes_api_key:
            problems.append("no Hermes API key (HERMES_API_KEY or API_SERVER_KEY in Hermes .env)")
        return problems


# --------------------------------------------------------------------------- hermes
class HermesClient:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self._health_lock = threading.Lock()
        self._health_cache: tuple[float, bool] = (0.0, False)

    def healthy(self, max_age: float = 10.0) -> bool:
        with self._health_lock:
            ts, ok = self._health_cache
            if time.monotonic() - ts < max_age:
                return ok
        ok = False
        try:
            req = urllib.request.Request(f"{self.cfg.hermes_url}/health", method="GET")
            with urllib.request.urlopen(req, timeout=2.5) as resp:
                ok = resp.status == 200
        except Exception as e:  # noqa: BLE001
            log.debug("hermes health probe failed: %s", e)
        with self._health_lock:
            self._health_cache = (time.monotonic(), ok)
        return ok

    def chat(self, agent_id: str, text: str) -> str:
        agent = self.cfg.agents.get(agent_id) or self.cfg.agents[self.cfg.default_agent]
        system_prompt = agent.get("system") or self.cfg.system_prompt
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": text})
        body: dict = {"messages": messages, "stream": False}
        model = agent.get("model") or self.cfg.hermes_model
        if model:
            body["model"] = model

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.cfg.hermes_api_key}",
            # Scopes Hermes long-term memory per keyboard agent persona.
            "X-Hermes-Session-Key": f"cornerstone-keyboard:{agent_id}",
        }
        req = urllib.request.Request(
            f"{self.cfg.hermes_url}/v1/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=self.cfg.hermes_timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        choices = payload.get("choices") or []
        if not choices:
            raise ValueError("hermes returned no choices")
        message = choices[0].get("message") or {}
        content = message.get("content")
        if isinstance(content, list):  # multimodal-style parts
            content = "".join(
                part.get("text", "") for part in content if isinstance(part, dict)
            )
        if not isinstance(content, str) or not content.strip():
            raise ValueError("hermes returned an empty answer")
        return content.strip()


# --------------------------------------------------------------------------- http
class Handler(BaseHTTPRequestHandler):
    server_version = f"{SERVICE_NAME}/1.0"
    cfg: Config
    hermes: HermesClient
    crash_log: Path

    # ---- helpers
    def _json(self, code: int, obj: dict | None = None) -> None:
        data = b"" if obj is None else json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _authorized(self) -> bool:
        auth = self.headers.get("Authorization", "")
        scheme, _, token = auth.partition(" ")
        return scheme.lower() == "bearer" and token.strip() == self.cfg.token

    def _read_json(self) -> dict | None:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 2_000_000:
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:  # noqa: BLE001
            return None

    def _path(self) -> str:
        return self.path.split("?", 1)[0].rstrip("/") or "/"

    def log_message(self, fmt: str, *args) -> None:  # route BaseHTTPRequestHandler logs
        log.info("%s %s", self.client_address[0], fmt % args)

    # ---- routes
    def do_GET(self) -> None:  # noqa: N802
        path = self._path()
        if path in ("/", "/health"):
            self._json(200, {
                "service": SERVICE_NAME,
                "status": "OPERATIONAL",
                "hermes": self.hermes.healthy(),
            })
        elif path == "/agents":
            online = self.hermes.healthy()
            self._json(200, {"agents": {
                agent_id: {
                    "name": spec.get("name", agent_id.title()),
                    "role": spec.get("role", "Hermes agent"),
                    "online": online,
                }
                for agent_id, spec in self.cfg.agents.items()
            }})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = self._path()
        if path == "/agentkey":
            self._handle_agentkey()
        elif path == "/crash":
            self._handle_crash()
        else:
            self._json(404, {"error": "not found"})

    def _handle_agentkey(self) -> None:
        if not self._authorized():
            self._json(401, {"error": "bad token"})
            return
        body = self._read_json()
        if not body or not isinstance(body.get("text"), str) or not body["text"].strip():
            self._json(400, {"error": "missing text"})
            return
        agent_id = str(body.get("agent") or self.cfg.default_agent).strip().lower()
        text = body["text"]
        started = time.monotonic()
        try:
            answer = self.hermes.chat(agent_id, text)
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            log.warning("hermes HTTP %s for agent=%s: %s", e.code, agent_id, detail)
            self._json(502, {"error": f"hermes http {e.code}", "detail": detail})
            return
        except Exception as e:  # noqa: BLE001
            log.warning("hermes call failed for agent=%s: %s", agent_id, e)
            self._json(502, {"error": f"hermes unreachable: {e}"})
            return
        log.info("agent=%s chars_in=%d chars_out=%d took=%.1fs",
                 agent_id, len(text), len(answer), time.monotonic() - started)
        self._json(200, {"answer": answer})

    def _handle_crash(self) -> None:
        if not self._authorized():
            self._json(401, {"error": "bad token"})
            return
        body = self._read_json() or {}
        trace = str(body.get("trace", ""))[:32000]
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        try:
            self.crash_log.parent.mkdir(parents=True, exist_ok=True)
            with self.crash_log.open("a", encoding="utf-8") as fh:
                fh.write(f"===== {stamp} from {self.client_address[0]}\n{trace}\n\n")
        except Exception as e:  # noqa: BLE001
            log.warning("could not write crash log: %s", e)
        self._json(204)


# --------------------------------------------------------------------------- main
def setup_logging(log_dir: str) -> None:
    Path(log_dir).mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    file_handler = logging.handlers.RotatingFileHandler(
        os.path.join(log_dir, "bridge.log"), maxBytes=2_000_000, backupCount=3, encoding="utf-8")
    file_handler.setFormatter(fmt)
    log.addHandler(file_handler)
    if sys.stdout and sys.stdout.isatty():
        console = logging.StreamHandler(sys.stdout)
        console.setFormatter(fmt)
        log.addHandler(console)
    log.setLevel(logging.INFO)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--env", default=DEFAULT_ENV_FILE, help="env file with HUB_TOKEN etc.")
    parser.add_argument("--agents", default=str(Path(__file__).with_name("hub-bridge.agents.json")),
                        help="optional agents JSON (roster + personas)")
    parser.add_argument("--check", action="store_true", help="validate config and Hermes reachability, then exit")
    args = parser.parse_args(argv)

    cfg = Config(args.env, args.agents)
    setup_logging(cfg.log_dir)
    problems = cfg.validate()
    if problems:
        for p in problems:
            log.error(p)
        return 2

    hermes = HermesClient(cfg)
    if args.check:
        ok = hermes.healthy(max_age=0)
        print(f"hermes {cfg.hermes_url}: {'OK' if ok else 'UNREACHABLE'}; "
              f"agents={','.join(cfg.agents)}; bind={cfg.bind}:{cfg.port}")
        return 0 if ok else 1

    Handler.cfg = cfg
    Handler.hermes = hermes
    Handler.crash_log = Path(cfg.log_dir) / "keyboard-crashes.log"
    server = ThreadingHTTPServer((cfg.bind, cfg.port), Handler)
    server.daemon_threads = True
    log.info("listening on %s:%d -> %s (agents: %s)", cfg.bind, cfg.port, cfg.hermes_url, ", ".join(cfg.agents))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
