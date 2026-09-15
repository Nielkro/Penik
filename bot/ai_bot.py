#!/usr/bin/env python3
"""
Penik E2EE AI Bot powered by DeepSeek via OpenAI-compatible API.
Features per-user conversation memory, command handling, and end-to-end encryption.
"""

import os
import sys
import time
import uuid
import json
import base64
import ctypes
import asyncio
import logging
import argparse
from pathlib import Path
from typing import Dict, Any, Optional, Tuple, List

import requests
import msgpack
import websockets

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("PenikAIBot")

# WebSocket Binary Opcodes
OP_MSG_SEND = 0x01
OP_MSG_RECV = 0x02
OP_MSG_ACK = 0x03
OP_MSG_DELIVERED = 0x04
OP_OFFLINE_BATCH = 0x05
OP_PING = 0x06
OP_PONG = 0x07
OP_MSG_READ = 0x18
OP_TYPING = 0x1f

PAIRWISE_INFO = b"penik-pairwise-message-v1"


# ─── Rust Crypto Core C-ABI Loader ───

def load_crypto_lib() -> ctypes.CDLL:
    candidates = [
        Path(__file__).resolve().parent.parent / "rust" / "penik-crypto" / "target" / "release" / "libpenik_crypto.so",
        Path(__file__).resolve().parent.parent / "rust" / "penik-crypto" / "target" / "debug" / "libpenik_crypto.so",
        Path("/usr/local/lib/libpenik_crypto.so"),
    ]
    lib_path = None
    for p in candidates:
        if p.exists():
            lib_path = p
            break

    if not lib_path:
        raise FileNotFoundError("libpenik_crypto.so not found. Please compile rust/penik-crypto.")

    lib = ctypes.CDLL(str(lib_path))

    lib.penik_generate_key_pair.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    lib.penik_generate_key_pair.restype = ctypes.c_int32

    lib.penik_derive_shared_secret.argtypes = [
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_void_p,
    ]
    lib.penik_derive_shared_secret.restype = ctypes.c_int32

    lib.penik_build_pairwise_aad.argtypes = [
        ctypes.c_uint64,
        ctypes.c_uint64,
        ctypes.c_char_p,
        ctypes.c_int64,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.penik_build_pairwise_aad.restype = ctypes.c_int32

    lib.penik_build_pairwise_aad_v2.argtypes = [
        ctypes.c_uint64,
        ctypes.c_uint64,
        ctypes.c_char_p,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.penik_build_pairwise_aad_v2.restype = ctypes.c_int32

    lib.penik_e2ee_encrypt.argtypes = [
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]
    lib.penik_e2ee_encrypt.restype = ctypes.c_int32

    lib.penik_e2ee_decrypt.argtypes = [
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.penik_e2ee_decrypt.restype = ctypes.c_int32

    lib.penik_decrypt_file.argtypes = [
        ctypes.c_char_p,
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.penik_decrypt_file.restype = ctypes.c_int32

    return lib


_crypto = load_crypto_lib()


def generate_key_pair() -> Tuple[bytes, bytes]:
    out_pub = (ctypes.c_uint8 * 32)()
    out_priv = (ctypes.c_uint8 * 32)()
    if _crypto.penik_generate_key_pair(out_pub, out_priv) != 0:
        raise RuntimeError("Failed to generate X25519 key pair")
    return bytes(out_priv), bytes(out_pub)


def derive_shared_secret(private_key: bytes, peer_public_key: bytes) -> bytes:
    out_secret = (ctypes.c_uint8 * 32)()
    if _crypto.penik_derive_shared_secret(private_key, peer_public_key, len(peer_public_key), out_secret) != 0:
        raise ValueError("Failed to derive Diffie-Hellman shared secret")
    return bytes(out_secret)


def build_pairwise_aad(sender_user_id: int, recipient_user_id: int, client_msg_id: str = "", timestamp: int = 0) -> bytes:
    msg_id_bytes = client_msg_id.encode("utf-8")
    out_len = ctypes.c_size_t(0)
    _crypto.penik_build_pairwise_aad(
        ctypes.c_uint64(sender_user_id),
        ctypes.c_uint64(recipient_user_id),
        msg_id_bytes,
        ctypes.c_int64(timestamp),
        None,
        ctypes.byref(out_len),
    )
    buf = (ctypes.c_uint8 * out_len.value)()
    if _crypto.penik_build_pairwise_aad(
        ctypes.c_uint64(sender_user_id),
        ctypes.c_uint64(recipient_user_id),
        msg_id_bytes,
        ctypes.c_int64(timestamp),
        buf,
        ctypes.byref(out_len),
    ) != 0:
        raise RuntimeError("Failed to build pairwise AAD")
    return bytes(buf)


def build_pairwise_aad_v2(sender_user_id: int, recipient_user_id: int, client_msg_id: str = "") -> bytes:
    msg_id_bytes = client_msg_id.encode("utf-8")
    out_len = ctypes.c_size_t(0)
    _crypto.penik_build_pairwise_aad_v2(
        ctypes.c_uint64(sender_user_id),
        ctypes.c_uint64(recipient_user_id),
        msg_id_bytes,
        None,
        ctypes.byref(out_len),
    )
    buf = (ctypes.c_uint8 * out_len.value)()
    if _crypto.penik_build_pairwise_aad_v2(
        ctypes.c_uint64(sender_user_id),
        ctypes.c_uint64(recipient_user_id),
        msg_id_bytes,
        buf,
        ctypes.byref(out_len),
    ) != 0:
        raise RuntimeError("Failed to build pairwise AAD v2")
    return bytes(buf)


def e2ee_encrypt(plaintext: str | bytes, shared_secret: bytes, aad: bytes = b"") -> Dict[str, bytes]:
    pt_bytes = plaintext.encode("utf-8") if isinstance(plaintext, str) else plaintext
    salt = os.urandom(16)
    nonce = os.urandom(12)

    out_ct_len = len(pt_bytes) + 16
    out_ct = (ctypes.c_uint8 * out_ct_len)()

    if _crypto.penik_e2ee_encrypt(
        pt_bytes,
        len(pt_bytes),
        shared_secret,
        salt,
        len(salt),
        nonce,
        len(nonce),
        PAIRWISE_INFO,
        len(PAIRWISE_INFO),
        aad,
        len(aad),
        out_ct,
    ) != 0:
        raise RuntimeError("Encryption failed in Rust core")

    return {
        "ciphertext": bytes(out_ct),
        "salt": salt,
        "nonce": nonce,
    }


def e2ee_decrypt(ciphertext: bytes, shared_secret: bytes, salt: bytes, nonce: bytes, aad: bytes = b"") -> bytes:
    if len(ciphertext) < 16:
        raise ValueError("Ciphertext too short")
    out_pt_len = ctypes.c_size_t(len(ciphertext) - 16)
    out_pt = (ctypes.c_uint8 * out_pt_len.value)()

    if _crypto.penik_e2ee_decrypt(
        ciphertext,
        len(ciphertext),
        shared_secret,
        salt,
        nonce,
        PAIRWISE_INFO,
        len(PAIRWISE_INFO),
        aad,
        len(aad),
        out_pt,
        ctypes.byref(out_pt_len),
    ) != 0:
        raise ValueError("Decryption failed: tag or AAD mismatch")

    return bytes(out_pt[:out_pt_len.value])


def decrypt_file(combined_encrypted_bytes: bytes, key: bytes) -> bytes:
    """Decrypts PCK1 chunked or legacy monolithic file payload using Rust penik-crypto."""
    if len(combined_encrypted_bytes) < 28:
        raise ValueError("Invalid encrypted file payload: too short")

    out_len = ctypes.c_size_t(0)
    _crypto.penik_decrypt_file(
        combined_encrypted_bytes,
        len(combined_encrypted_bytes),
        key,
        None,
        ctypes.byref(out_len),
    )
    if out_len.value == 0:
        return b""

    buf = (ctypes.c_uint8 * out_len.value)()
    res = _crypto.penik_decrypt_file(
        combined_encrypted_bytes,
        len(combined_encrypted_bytes),
        key,
        buf,
        ctypes.byref(out_len),
    )
    if res != 0:
        raise ValueError("penik_decrypt_file failed in Rust core: tag mismatch")

    return bytes(buf[:out_len.value])


def extract_video_frames(video_bytes: bytes, max_frames: int = 6) -> List[str]:
    """Extracts evenly spaced JPEG frames from video bytes using ffmpeg and returns base64 strings."""
    import tempfile
    import subprocess

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp_path = Path(tmpdir)
        video_file = tmp_path / "input.mp4"
        with open(video_file, "wb") as f:
            f.write(video_bytes)

        frame_pattern = str(tmp_path / "frame_%03d.jpg")
        cmd = [
            "ffmpeg", "-y", "-i", str(video_file),
            "-vf", "scale=min(iw\\,720):-2",
            "-vframes", str(max_frames),
            "-q:v", "3",
            frame_pattern
        ]
        try:
            subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        except Exception as e:
            logger.error(f"ffmpeg execution failed: {e}")
            return []

        frames = []
        for frame_file in sorted(tmp_path.glob("frame_*.jpg")):
            try:
                with open(frame_file, "rb") as f:
                    f_bytes = f.read()
                frames.append(base64.b64encode(f_bytes).decode("ascii"))
            except Exception as e:
                logger.warning(f"Failed to read extracted frame {frame_file}: {e}")

        return frames


# ─── AI Client Helper ───

class AIClient:
    def __init__(self, base_url: str, api_key: str, model: str):
        self.api_url = f"{base_url.rstrip('/')}/chat/completions"
        self.api_key = api_key
        self.model = model
        self.session = requests.Session()

    def generate_reply(self, messages: List[Dict[str, str]]) -> str:
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": 0.7,
        }
        try:
            res = self.session.post(self.api_url, headers=headers, json=payload, timeout=60)
            if res.status_code != 200:
                logger.error(f"AI API returned error [{res.status_code}]: {res.text}")
                return f"⚠️ Ошибка вызова нейросети ({res.status_code}). Попробуйте позже."
            data = res.json()
            choices = data.get("choices", [])
            if choices and "message" in choices[0]:
                return choices[0]["message"].get("content", "").strip()
            return "⚠️ Не удалось получить ответ от нейросети."
        except Exception as e:
            logger.error(f"AI request exception: {e}")
            return f"⚠️ Ошибка соединения с AI API: {e}"


# ─── Penik AI Bot Class ───

SYSTEM_PROMPT = (
    "Ты — полезный, умный и вежливый AI-ассистент в защищенном мессенджере Penik. "
    "Отвечай емко, по делу и структурированно на русском языке, используй markdown-разметку при необходимости."
)

class PenikAIBot:
    def __init__(
        self,
        server_url: str,
        token: str,
        ai_client: AIClient,
        identity_file: str = "ai_bot_identity.json",
        max_history: int = 20,
    ):
        self.server_url = server_url.rstrip("/")
        self.ws_url = self.server_url.replace("http://", "ws://").replace("https://", "wss://") + "/api/v1/ws"
        self.token = token
        self.ai_client = ai_client
        self.identity_file = identity_file
        self.max_history = max_history

        self.user_id: Optional[int] = None
        self.device_id: Optional[int] = None
        self.name: Optional[str] = None
        self.nickname: Optional[str] = None

        self.private_key, self.public_key = self._load_or_create_identity()
        self._shared_secrets_cache: Dict[bytes, bytes] = {}
        self.user_histories: Dict[int, List[Dict[str, str]]] = {}
        self.seen_msg_ids: set = set()
        self.ws: Optional[websockets.WebSocketClientProtocol] = None
        self.session = requests.Session()

    def _load_or_create_identity(self) -> Tuple[bytes, bytes]:
        p = Path(self.identity_file)
        if p.exists():
            try:
                with open(p, "r", encoding="utf-8") as f:
                    data = json.load(f)
                priv = base64.b64decode(data["private_key"])
                pub = base64.b64decode(data["public_key"])
                logger.info(f"Loaded existing identity key from {self.identity_file}")
                return priv, pub
            except Exception as e:
                logger.warning(f"Failed to load {self.identity_file}: {e}. Generating new identity.")

        priv, pub = generate_key_pair()
        with open(p, "w", encoding="utf-8") as f:
            json.dump({
                "private_key": base64.b64encode(priv).decode("ascii"),
                "public_key": base64.b64encode(pub).decode("ascii"),
            }, f, indent=2)
        logger.info(f"Saved new identity key to {self.identity_file}")
        return priv, pub

    def _auth_headers(self) -> Dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}

    def init_account(self):
        """Fetches bot profile and updates server with public identity key."""
        me_res = self.session.get(f"{self.server_url}/api/v1/users/me", headers=self._auth_headers())
        if me_res.status_code == 200:
            me = me_res.json()
            self.user_id = me["id"]
            self.device_id = me.get("device_id")
            self.name = me.get("name")
            self.nickname = me.get("nickname")
        else:
            dev_res = self.session.get(f"{self.server_url}/api/v1/devices", headers=self._auth_headers())
            if dev_res.status_code != 200:
                raise RuntimeError(f"Authentication failed ({dev_res.status_code}): {dev_res.text}")
            devices = dev_res.json()
            if not devices:
                raise RuntimeError("No device associated with bot token")
            current_dev = next((d for d in devices if d.get("is_current")), devices[0])
            self.device_id = current_dev["id"]

        # Publish identity key to server
        pub_b64 = base64.b64encode(self.public_key).decode("ascii")
        res = self.session.post(
            f"{self.server_url}/api/v1/keys/init",
            headers=self._auth_headers(),
            json={"ik_pub": pub_b64, "crypto_version": 2},
        )
        if res.status_code not in (200, 204):
            logger.error(f"Failed to publish bot public key ({res.status_code}): {res.text}")
        else:
            logger.info("Published bot public identity key to server.")

        logger.info(f"Bot authenticated as @{self.nickname} (Name: '{self.name}', User ID: {self.user_id}, Device ID: {self.device_id})")

    def get_shared_secret(self, peer_pub_bytes: bytes) -> bytes:
        if peer_pub_bytes not in self._shared_secrets_cache:
            self._shared_secrets_cache[peer_pub_bytes] = derive_shared_secret(self.private_key, peer_pub_bytes)
        return self._shared_secrets_cache[peer_pub_bytes]

    def get_user_key_bundle(self, target_user_id: int) -> Dict[str, Any]:
        res = self.session.get(f"{self.server_url}/api/v1/keys/bundle/{target_user_id}", headers=self._auth_headers())
        if res.status_code != 200:
            raise RuntimeError(f"Failed to fetch key bundle for user {target_user_id}: {res.text}")
        return res.json()

    async def send_frame(self, opcode: int, payload: Any):
        if not self.ws:
            return
        payload_bytes = msgpack.packb(payload, use_bin_type=True)
        frame = bytes([opcode]) + payload_bytes
        await self.ws.send(frame)

    def _extract_pub_key(self, dev: Dict[str, Any]) -> Optional[bytes]:
        raw = dev.get("identity_key") or dev.get("ik_pub") or dev.get("x25519_pub")
        if not raw:
            return None
        if isinstance(raw, (bytes, bytearray)):
            return bytes(raw)
        if isinstance(raw, str):
            try:
                return base64.b64decode(raw)
            except Exception:
                return None
        return None

    async def send_typing(self, recipient_user_id: int, is_typing: bool = True):
        """Sends typing status indicator to recipient."""
        await self.send_frame(OP_TYPING, {
            "to_user_id": recipient_user_id,
            "is_typing": is_typing,
        })

    async def send_message(self, recipient_user_id: int, text: str, reply_to_msg_id: Optional[str] = None, min_ts: int = 0) -> str:
        """Encrypts and sends a message to all devices of recipient_user_id."""
        bundle = self.get_user_key_bundle(recipient_user_id)
        devices = bundle.get("devices", [])
        if not devices:
            raise RuntimeError(f"Recipient user {recipient_user_id} has no registered devices")

        client_msg_id = str(uuid.uuid4())
        now = int(time.time())
        # In a production bot, timestamps should ideally be sent with millisecond precision.
        # Ensuring now >= min_ts + 1 guarantees strictly monotonic ordering across all clients.
        if min_ts > 0 and now <= min_ts:
            now = min_ts + 1

        devices_payload = []
        for dev in devices:
            dev_id = dev["device_id"]
            peer_pub = self._extract_pub_key(dev)
            if not peer_pub:
                continue
            shared_secret = self.get_shared_secret(peer_pub)

            # Build AAD v1 with timestamp
            aad = build_pairwise_aad(self.user_id, recipient_user_id, client_msg_id, now)
            enc = e2ee_encrypt(text, shared_secret, aad=aad)

            devices_payload.append({
                "device_id": dev_id,
                "ciphertext": enc["ciphertext"],
                "salt": enc["salt"],
                "nonce": enc["nonce"],
            })

        if not devices_payload:
            raise RuntimeError("Could not encrypt for any device of recipient")

        frame_payload = {
            "to_user_id": recipient_user_id,
            "msg_id": client_msg_id,
            "reply_to_msg_id": reply_to_msg_id,
            "created_at": now,
            "devices": devices_payload,
        }

        await self.send_frame(OP_MSG_SEND, frame_payload)
        logger.info(f"Sent reply to User #{recipient_user_id} (msg_id: {client_msg_id[:8]}...)")
        return client_msg_id

    def decrypt_payload(self, msg_payload: Dict[str, Any], sender_pub_bytes: bytes) -> Optional[str]:
        sender_id = msg_payload["from_user_id"]
        client_msg_id = msg_payload.get("client_msg_id", "")
        ts = msg_payload.get("ts") or msg_payload.get("created_at") or 0
        ciphertext = msg_payload["ciphertext"]
        salt = msg_payload["salt"]
        nonce = msg_payload["nonce"]

        shared_secret = self.get_shared_secret(sender_pub_bytes)

        # Try AAD v1 (timestamp-bound)
        try:
            aad_v1 = build_pairwise_aad(sender_id, self.user_id, client_msg_id, ts)
            pt = e2ee_decrypt(ciphertext, shared_secret, salt, nonce, aad=aad_v1)
            return pt.decode("utf-8")
        except Exception:
            pass

        # Try AAD v2 (clock-independent)
        try:
            aad_v2 = build_pairwise_aad_v2(sender_id, self.user_id, client_msg_id)
            pt = e2ee_decrypt(ciphertext, shared_secret, salt, nonce, aad=aad_v2)
            return pt.decode("utf-8")
        except Exception:
            pass

        # Try empty AAD fallback
        try:
            pt = e2ee_decrypt(ciphertext, shared_secret, salt, nonce, aad=b"")
            return pt.decode("utf-8")
        except Exception as e:
            logger.error(f"Decryption failed: {e}")
            return None

    def _get_history(self, user_id: int) -> List[Dict[str, Any]]:
        if user_id not in self.user_histories:
            self.user_histories[user_id] = [{"role": "system", "content": SYSTEM_PROMPT}]
        return self.user_histories[user_id]

    def download_attachment(self, file_url: str) -> bytes:
        """Downloads encrypted attachment ciphertext from Penik server."""
        if file_url.startswith("/"):
            url = f"{self.server_url}{file_url}"
        else:
            url = file_url
        res = self.session.get(url, headers=self._auth_headers())
        if res.status_code != 200:
            raise RuntimeError(f"Failed to download attachment ({res.status_code}): {res.text}")
        return res.content

    async def handle_message(self, sender_user_id: int, text: str, msg_id: str, incoming_ts: int = 0):
        """Processes decrypted message, queries AI (with Vision & Video support), and sends response."""
        logger.info(f"Incoming message from User #{sender_user_id}: {text!r}")
        trimmed = text.strip()

        if trimmed == "/start":
            reply = (
                "👋 Привет! Я AI-ассистент на базе **DeepSeek (v4.1 Flash)** в защищенном мессенджере Penik 🔐🧠\n\n"
                "Я умею:\n"
                "• Отвечать на любые вопросы и помогать с кодом/текстами\n"
                "• Анализировать **фотографии** и изображения 📸\n"
                "• Смотреть и разбирать **видео** по раскадровке 🎬\n\n"
                "Доступные команды:\n"
                "• `/clear` или `/reset` — очистить контекст диалога\n"
                "• `/info` — информация о модели и E2EE-защите\n"
                "• `/help` — список команд"
            )
            self.user_histories[sender_user_id] = [{"role": "system", "content": SYSTEM_PROMPT}]
            await self.send_message(sender_user_id, reply, reply_to_msg_id=msg_id, min_ts=incoming_ts)
            return

        if trimmed in ("/clear", "/reset"):
            self.user_histories[sender_user_id] = [{"role": "system", "content": SYSTEM_PROMPT}]
            reply = "🧹 Контекст диалога очищен. Можем начать новую беседу!"
            await self.send_message(sender_user_id, reply, reply_to_msg_id=msg_id, min_ts=incoming_ts)
            return

        if trimmed == "/info":
            reply = (
                f"🧠 **AI Bot Info**\n"
                f"• Model: `{self.ai_client.model}`\n"
                f"• Vision & Video: Поддерживается (авто-раскадровка видео через ffmpeg)\n"
                f"• Provider: `plusvibeapi.ru` (OpenAI API compatible)\n"
                f"• Encryption: End-to-End (X25519 + ChaCha20-Poly1305)\n"
                f"• Memory: до {self.max_history} сообщений в контексте"
            )
            await self.send_message(sender_user_id, reply, reply_to_msg_id=msg_id, min_ts=incoming_ts)
            return

        if trimmed == "/help":
            reply = "Команды:\n/start — начало работы\n/clear — очистить контекст\n/info — о боте\n/help — помощь"
            await self.send_message(sender_user_id, reply, reply_to_msg_id=msg_id, min_ts=incoming_ts)
            return

        # Show typing status
        await self.send_typing(sender_user_id, is_typing=True)

        user_content: Any = text

        # Check for media attachments (photo or video)
        if trimmed.startswith("{"):
            try:
                parsed = json.loads(trimmed)
                if parsed.get("type") == "fwd":
                    text = parsed.get("text", "")
                    user_content = text
                elif parsed.get("type") == "file" or parsed.get("file"):
                    file_info = parsed.get("file") or parsed
                    file_url = file_info.get("url")
                    file_key_b64 = file_info.get("key")
                    mime_type = file_info.get("mime_type", "")
                    caption = parsed.get("text") or file_info.get("caption") or ""

                    if file_url and file_key_b64:
                        logger.info(f"Downloading attachment {file_url} (MIME: {mime_type})...")
                        raw_encrypted = await asyncio.to_thread(self.download_attachment, file_url)
                        file_key = base64.b64decode(file_key_b64)
                        decrypted = await asyncio.to_thread(decrypt_file, raw_encrypted, file_key)

                        if mime_type.startswith("image/"):
                            logger.info(f"Processing image attachment ({len(decrypted)} bytes)...")
                            img_b64 = base64.b64encode(decrypted).decode("ascii")
                            prompt_text = caption.strip() if caption.strip() else "Опиши подробно, что изображено на этом изображении, и ответь на любые вопросы."
                            user_content = [
                                {"type": "text", "text": prompt_text},
                                {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{img_b64}"}}
                            ]
                        elif mime_type.startswith("video/"):
                            logger.info(f"Extracting frames from video attachment ({len(decrypted)} bytes)...")
                            frames = await asyncio.to_thread(extract_video_frames, decrypted, 6)
                            prompt_text = caption.strip() if caption.strip() else "Посмотри на эти кадры из видео по порядку. Опиши подробно, что происходит на видео."
                            content_list = [{"type": "text", "text": prompt_text}]
                            for f_b64 in frames:
                                content_list.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{f_b64}"}})
                            user_content = content_list
                            logger.info(f"Extracted {len(frames)} frames for AI vision analysis.")
            except Exception as e:
                logger.error(f"Error processing attachment: {e}", exc_info=True)

        # Prepare messages
        history = self._get_history(sender_user_id)
        history.append({"role": "user", "content": user_content})

        # Trim history if too long (keep system prompt + last N messages)
        if len(history) > self.max_history + 1:
            history = [history[0]] + history[-self.max_history:]
            self.user_histories[sender_user_id] = history

        # Call AI in background thread
        ai_reply = await asyncio.to_thread(self.ai_client.generate_reply, history)

        # Save assistant response to memory
        history.append({"role": "assistant", "content": ai_reply})

        await self.send_typing(sender_user_id, is_typing=False)
        await self.send_message(sender_user_id, ai_reply, reply_to_msg_id=msg_id, min_ts=incoming_ts)

    async def _process_incoming_msg(self, payload: Dict[str, Any]):
        sender_id = payload.get("from_user_id")
        server_msg_id = payload.get("msg_id") or payload.get("id") or payload.get("server_msg_id") or 0
        client_msg_id = payload.get("client_msg_id", "")
        ts = payload.get("ts") or payload.get("created_at") or 0

        # Always acknowledge delivery & read to server
        if server_msg_id > 0:
            await self.send_frame(OP_MSG_DELIVERED, {"msg_id": server_msg_id, "chat_user_id": sender_id})
            await self.send_frame(OP_MSG_READ, {"chat_user_id": sender_id, "up_to_msg_id": server_msg_id})

        # Deduplicate incoming messages so offline batches / duplicate frames aren't re-answered
        dedup_key = client_msg_id or (str(server_msg_id) if server_msg_id > 0 else "")
        if dedup_key:
            if dedup_key in self.seen_msg_ids:
                logger.info(f"Skipping already processed message: {dedup_key}")
                return
            self.seen_msg_ids.add(dedup_key)
            if len(self.seen_msg_ids) > 10000:
                self.seen_msg_ids.pop()

        # Fetch sender's public key
        bundle = self.get_user_key_bundle(sender_id)
        sender_dev_id = payload.get("from_device_id")
        sender_pub = None
        for d in bundle.get("devices", []):
            if d.get("device_id") == sender_dev_id:
                sender_pub = self._extract_pub_key(d)
                break
        if not sender_pub and bundle.get("devices"):
            sender_pub = self._extract_pub_key(bundle["devices"][0])

        if not sender_pub:
            logger.warning(f"Could not find public key for sender #{sender_id}")
            return

        decrypted_text = self.decrypt_payload(payload, sender_pub)

        if decrypted_text is not None:
            await self.handle_message(sender_id, decrypted_text, client_msg_id, incoming_ts=ts)

    async def run_forever(self):
        """Main WebSocket connect & message loop with auto-reconnect."""
        self.init_account()

        while True:
            try:
                logger.info(f"Connecting to WebSocket: {self.ws_url}")
                subprotocols = ["access_token", self.token]
                headers = {"Authorization": f"Bearer {self.token}"}

                connect_kwargs = {
                    "subprotocols": subprotocols,
                    "ping_interval": 30,
                    "ping_timeout": 10,
                }
                import inspect
                sig = inspect.signature(websockets.connect)
                if "additional_headers" in sig.parameters:
                    connect_kwargs["additional_headers"] = headers
                else:
                    connect_kwargs["extra_headers"] = headers

                async with websockets.connect(self.ws_url, **connect_kwargs) as ws:
                    self.ws = ws
                    logger.info("🟢 WebSocket connected! AI Bot is listening for questions...")

                    while True:
                        raw = await ws.recv()
                        if isinstance(raw, str) or len(raw) == 0:
                            continue
                        opcode = raw[0]
                        payload = {}
                        if len(raw) > 1:
                            try:
                                payload = msgpack.unpackb(raw[1:], raw=False)
                            except Exception as e:
                                logger.debug(f"MsgPack unpack skipped for opcode 0x{opcode:02x}: {e}")
                                payload = {}

                        if opcode == OP_MSG_RECV:
                            asyncio.create_task(self._process_incoming_msg(payload))
                        elif opcode == OP_OFFLINE_BATCH:
                            msgs = payload.get("msgs", []) or payload.get("Msgs", [])
                            for m in msgs:
                                asyncio.create_task(self._process_incoming_msg(m))
                        elif opcode == OP_PING:
                            await self.send_frame(OP_PONG, {})

            except (websockets.ConnectionClosed, asyncio.TimeoutError, ConnectionRefusedError) as e:
                logger.warning(f"WebSocket disconnected ({e}). Reconnecting in 3s...")
                await asyncio.sleep(3)
            except Exception as e:
                logger.error(f"Unexpected error in loop: {e}", exc_info=True)
                await asyncio.sleep(5)


# ─── Helper for Bot Creation ───

def create_bot_via_api(server_url: str, user_token: str, name: str, nickname: str) -> str:
    """Helper creating a new bot account via POST /api/v1/bots and returning the bot token."""
    res = requests.post(
        f"{server_url.rstrip('/')}/api/v1/bots",
        headers={"Authorization": f"Bearer {user_token}"},
        json={"name": name, "nickname": nickname},
    )
    if res.status_code != 201:
        raise RuntimeError(f"Failed to create bot ({res.status_code}): {res.text}")
    data = res.json()
    logger.info(f"AI Bot created successfully! ID: {data['bot_id']}, Nickname: @{data['nickname']}, Token: {data['token']}")
    return data["token"]


# ─── Main Entry Point ───

def main():
    parser = argparse.ArgumentParser(description="Penik E2EE DeepSeek AI Bot")
    parser.add_argument("--server", default=os.getenv("PENIK_SERVER_URL", "http://localhost:8143"), help="Server base URL")
    parser.add_argument("--token", default=os.getenv("PENIK_BOT_TOKEN", ""), help="Bot API Token (bot_...)")
    parser.add_argument("--identity", default="ai_bot_identity.json", help="Path to bot identity keyfile")

    # OpenAI-compatible API configurations
    parser.add_argument("--openai-base-url", default=os.getenv("OPENAI_BASE_URL", "https://plusvibeapi.ru/v1"), help="AI API Base URL")
    parser.add_argument("--openai-api-key", default=os.getenv("OPENAI_API_KEY", ""), help="AI API Key")
    parser.add_argument("--model", default=os.getenv("AI_MODEL", "deepseek-v4.1-flash"), help="Model name")

    # Arguments for creating a new bot
    parser.add_argument("--create", action="store_true", help="Create a new bot via user token")
    parser.add_argument("--user-token", default="", help="User token for creating the bot")
    parser.add_argument("--name", default="DeepSeek AI", help="Bot display name")
    parser.add_argument("--nickname", default="deepseek_bot", help="Bot unique nickname")

    args = parser.parse_args()

    token = args.token
    if args.create:
        if not args.user_token:
            logger.error("--user-token is required when using --create")
            sys.exit(1)
        token = create_bot_via_api(args.server, args.user_token, args.name, args.nickname)

    if not token:
        logger.error("No bot token provided. Pass --token <bot_token> or use --create with --user-token.")
        sys.exit(1)

    ai_client = AIClient(
        base_url=args.openai_base_url,
        api_key=args.openai_api_key,
        model=args.model,
    )

    bot = PenikAIBot(
        server_url=args.server,
        token=token,
        ai_client=ai_client,
        identity_file=args.identity,
    )

    try:
        asyncio.run(bot.run_forever())
    except KeyboardInterrupt:
        logger.info("AI Bot stopped by user.")


if __name__ == "__main__":
    main()
