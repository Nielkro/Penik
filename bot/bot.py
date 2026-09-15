#!/usr/bin/env python3
"""
Penik E2EE Bot Prototype
Standalone Python bot running over WebSocket with ***REDACTED-BY-FILTER-REPO*** / X25519 cryptography.
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
logger = logging.getLogger("PenikBot")

# WebSocket Binary Opcodes
OP_MSG_SEND = 0x01
OP_MSG_RECV = 0x02
OP_MSG_ACK = 0x03
OP_MSG_DELIVERED = 0x04
OP_OFFLINE_BATCH = 0x05
OP_PING = 0x06
OP_PONG = 0x07
OP_MSG_READ = 0x18

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
    if isinstance(plaintext, str):
        plaintext = plaintext.encode("utf-8")
    pt_len = len(plaintext)
    ct_buf = (ctypes.c_uint8 * (pt_len + 16))()
    salt_buf = (ctypes.c_uint8 * 32)()
    nonce_buf = (ctypes.c_uint8 * 12)()

    if _crypto.penik_e2ee_encrypt(
        plaintext,
        pt_len,
        shared_secret,
        PAIRWISE_INFO,
        len(PAIRWISE_INFO),
        aad,
        len(aad),
        ct_buf,
        salt_buf,
        nonce_buf,
    ) != 0:
        raise RuntimeError("Failed to encrypt with ***REDACTED-BY-FILTER-REPO***")

    return {
        "ciphertext": bytes(ct_buf),
        "salt": bytes(salt_buf),
        "nonce": bytes(nonce_buf),
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


# ─── Penik Bot Class ───

class PenikBot:
    def __init__(self, server_url: str, token: str, identity_file: str = "bot_identity.json"):
        self.server_url = server_url.rstrip("/")
        self.ws_url = self.server_url.replace("http://", "ws://").replace("https://", "wss://") + "/api/v1/ws"
        self.token = token
        self.identity_file = identity_file

        self.user_id: Optional[int] = None
        self.device_id: Optional[int] = None
        self.name: Optional[str] = None
        self.nickname: Optional[str] = None

        self.private_key, self.public_key = self._load_or_create_identity()
        self._shared_secrets_cache: Dict[bytes, bytes] = {}
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
        self.session.post(
            f"{self.server_url}/api/v1/keys/init",
            headers=self._auth_headers(),
            json={"ik_pub": pub_b64},
        )

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

    async def send_message(self, recipient_user_id: int, text: str, reply_to_msg_id: Optional[str] = None) -> str:
        """Encrypts and sends a message to all devices of recipient_user_id."""
        bundle = self.get_user_key_bundle(recipient_user_id)
        devices = bundle.get("devices", [])
        if not devices:
            raise RuntimeError(f"Recipient user {recipient_user_id} has no registered devices")

        client_msg_id = str(uuid.uuid4())
        now = int(time.time())

        devices_payload = []
        for dev in devices:
            dev_id = dev["device_id"]
            ik_pub_b64 = dev.get("ik_pub") or dev.get("x25519_pub")
            if not ik_pub_b64:
                continue
            peer_pub = base64.b64decode(ik_pub_b64)
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

    async def handle_message(self, sender_user_id: int, text: str, msg_id: str):
        """Processes decrypted message and sends appropriate response."""
        logger.info(f"Incoming message from User #{sender_user_id}: {text!r}")
        trimmed = text.strip()

        if trimmed == "/start":
            reply = (
                "👋 Привет! Я бот Penik со сквозным шифрованием (E2EE) 🔐\n\n"
                "Отправь мне любое сообщение, и я отвечу эхом.\n"
                "Доступные команды:\n"
                "• /ping — проверить пинг и статус бота\n"
                "• /info — техническая информация о шифровании\n"
                "• /help — список команд"
            )
        elif trimmed == "/ping":
            start = time.perf_counter()
            reply = f"🏓 Pong! Бот онлайн и готов к работе."
        elif trimmed == "/info":
            reply = (
                "🤖 Penik E2EE Bot\n"
                "• Protocol: MsgPack over Binary WebSocket\n"
                "• Key Agreement: X25519 Diffie-Hellman\n"
                "• Cipher: ***REDACTED-BY-FILTER-REPO*** AEAD\n"
                "• Core: Rust `penik-crypto` micro-core"
            )
        elif trimmed == "/help":
            reply = "Доступные команды: /start, /ping, /info, /help"
        else:
            reply = f"🤖 Эхо: {text}"

        await self.send_message(sender_user_id, reply, reply_to_msg_id=msg_id)

    async def _process_incoming_msg(self, payload: Dict[str, Any]):
        sender_id = payload.get("from_user_id")
        server_msg_id = payload.get("id") or payload.get("server_msg_id") or 0
        client_msg_id = payload.get("client_msg_id", "")

        # Acknowledge delivery & read
        if server_msg_id > 0:
            await self.send_frame(OP_MSG_DELIVERED, {"msg_id": server_msg_id, "chat_user_id": sender_id})
            await self.send_frame(OP_MSG_READ, {"chat_user_id": sender_id, "up_to_msg_id": server_msg_id})

        # Fetch sender's public key
        bundle = self.get_user_key_bundle(sender_id)
        sender_dev_id = payload.get("from_device_id")
        sender_pub_b64 = None
        for d in bundle.get("devices", []):
            if d.get("device_id") == sender_dev_id:
                sender_pub_b64 = d.get("ik_pub") or d.get("x25519_pub")
                break
        if not sender_pub_b64 and bundle.get("devices"):
            sender_pub_b64 = bundle["devices"][0].get("ik_pub") or bundle["devices"][0].get("x25519_pub")

        if not sender_pub_b64:
            logger.warning(f"Could not find public key for sender #{sender_id}")
            return

        sender_pub = base64.b64decode(sender_pub_b64)
        decrypted_text = self.decrypt_payload(payload, sender_pub)

        if decrypted_text is not None:
            await self.handle_message(sender_id, decrypted_text, client_msg_id)

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
                    logger.info("🟢 WebSocket connected! Bot is listening for messages...")

                    while True:
                        raw = await ws.recv()
                        if isinstance(raw, str):
                            continue
                        opcode = raw[0]
                        payload = msgpack.unpackb(raw[1:], raw=False)

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
    logger.info(f"Bot created successfully! ID: {data['bot_id']}, Nickname: @{data['nickname']}, Token: {data['token']}")
    return data["token"]


# ─── Main Entry Point ───

def main():
    parser = argparse.ArgumentParser(description="Penik E2EE Bot Prototype")
    parser.add_argument("--server", default=os.getenv("PENIK_SERVER_URL", "http://localhost:8143"), help="Server base URL")
    parser.add_argument("--token", default=os.getenv("PENIK_BOT_TOKEN", ""), help="Bot API Token (bot_...)")
    parser.add_argument("--identity", default="bot_identity.json", help="Path to bot identity keyfile")

    # Arguments for creating a new bot
    parser.add_argument("--create", action="store_true", help="Create a new bot via user token")
    parser.add_argument("--user-token", default="", help="User token for creating the bot")
    parser.add_argument("--name", default="Echo Bot", help="Bot display name")
    parser.add_argument("--nickname", default="echo_bot", help="Bot unique nickname")

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

    bot = PenikBot(server_url=args.server, token=token, identity_file=args.identity)

    try:
        asyncio.run(bot.run_forever())
    except KeyboardInterrupt:
        logger.info("Bot stopped by user.")


if __name__ == "__main__":
    main()
