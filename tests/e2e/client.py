"""
E2E Client implementation for Penik Messenger.
Provides full HTTP REST and WebSocket communication with cryptographic support.
"""

import os
import time
import uuid
import base64
import asyncio
import requests
import msgpack
import websockets
from typing import Optional, Dict, Any, Tuple, List

from .crypto_utils import (
    generate_key_pair,
    derive_shared_secret,
    build_pairwise_aad,
    e2ee_encrypt,
    e2ee_decrypt,
    encrypt_file,
    decrypt_file,
)


# WS binary opcodes from server/internal/ws/protocol.go
OP_MSG_SEND = 0x01
OP_MSG_RECV = 0x02
OP_MSG_ACK = 0x03
OP_MSG_DELIVERED = 0x04
OP_OFFLINE_BATCH = 0x05
OP_PING = 0x06
OP_PONG = 0x07
OP_KEY_FETCH_REQ = 0x10
OP_KEY_FETCH_RESP = 0x11
OP_MSG_READ = 0x18
OP_GROUP_MSG_SEND = 0x20
OP_GROUP_MSG_RECV = 0x21
OP_GROUP_MSG_ACK = 0x22


class PenikClient:
    """Simulates a full Penik Messenger client (web/mobile)."""

    def __init__(self, base_url: str = "http://localhost:8143", ws_url: str = "ws://localhost:8143/api/v1/ws"):
        self.base_url = base_url.rstrip("/")
        self.ws_url = ws_url
        self.session = requests.Session()
        self.token: Optional[str] = None
        self.user_id: Optional[int] = None
        self.device_id: Optional[int] = None
        self.nickname: Optional[str] = None
        self.name: Optional[str] = None

        # X25519 identity keys
        self.private_key, self.public_key_bytes = generate_key_pair()

        # Active websocket connection
        self.ws: Optional[websockets.WebSocketClientProtocol] = None
        self._shared_secrets_cache: Dict[bytes, bytes] = {}

    @property
    def public_key_b64(self) -> str:
        return base64.b64encode(self.public_key_bytes).decode("ascii")

    def _auth_headers(self) -> Dict[str, str]:
        if not self.token:
            return {}
        return {"Authorization": f"Bearer {self.token}"}

    # ── REST API ──

    def register(
        self,
        nickname: str,
        password: str = "P@ssw0rd123!",
        name: str = "Test User",
        device_name: str = "Python-TestDevice",
        platform: str = "linux",
        location: str = "Localhost"
    ) -> Dict[str, Any]:
        """Registers a new account with the generated identity key."""
        url = f"{self.base_url}/api/v1/register"
        payload = {
            "name": name,
            "nickname": nickname,
            "password": password,
            "device_name": device_name,
            "platform": platform,
            "location": location,
            "ik_pub": self.public_key_b64,
        }
        res = self.session.post(url, json=payload)
        if res.status_code not in (200, 201):
            raise RuntimeError(f"Register failed [{res.status_code}]: {res.text}")

        data = res.json()
        self.token = data["token"]
        self.user_id = data["user_id"]
        self.device_id = data["device_id"]
        self.nickname = nickname
        self.name = name
        return data

    def login(
        self,
        nickname: str,
        password: str = "P@ssw0rd123!",
        device_name: str = "Python-TestDevice",
        platform: str = "linux"
    ) -> Dict[str, Any]:
        """Logs in to an existing account."""
        url = f"{self.base_url}/api/v1/login"
        payload = {
            "nickname": nickname,
            "password": password,
            "device_name": device_name,
            "platform": platform,
            "location": "Localhost",
            "ik_pub": self.public_key_b64,
        }
        res = self.session.post(url, json=payload)
        if res.status_code != 200:
            raise RuntimeError(f"Login failed [{res.status_code}]: {res.text}")

        data = res.json()
        self.token = data["token"]
        self.user_id = data["user_id"]
        self.device_id = data["device_id"]
        self.nickname = nickname
        return data

    def get_me(self) -> Dict[str, Any]:
        """Fetches current user profile."""
        url = f"{self.base_url}/api/v1/users/{self.user_id}"
        res = self.session.get(url, headers=self._auth_headers())
        if res.status_code != 200:
            raise RuntimeError(f"get_me failed [{res.status_code}]: {res.text}")
        return res.json()

    def get_key_bundle(self, target_user_id: int) -> Dict[str, Any]:
        """Fetches identity key bundle for a user's devices."""
        url = f"{self.base_url}/api/v1/keys/bundle/{target_user_id}"
        res = self.session.get(url, headers=self._auth_headers())
        if res.status_code != 200:
            raise RuntimeError(f"get_key_bundle failed [{res.status_code}]: {res.text}")
        return res.json()

    def upload_attachment(self, file_bytes: bytes, filename: str = "file.bin") -> Dict[str, Any]:
        """Uploads encrypted file attachment via multipart/form-data."""
        url = f"{self.base_url}/api/v1/attachments/upload"
        files = {"file": (filename, file_bytes, "application/octet-stream")}
        res = self.session.post(url, headers=self._auth_headers(), files=files)
        if res.status_code not in (200, 201):
            raise RuntimeError(f"upload_attachment failed [{res.status_code}]: {res.text}")
        return res.json()

    def download_attachment(self, attachment_id: str) -> bytes:
        """Downloads raw encrypted file attachment."""
        url = f"{self.base_url}/api/v1/attachments/file/{attachment_id}"
        res = self.session.get(url, headers=self._auth_headers())
        if res.status_code != 200:
            raise RuntimeError(f"download_attachment failed [{res.status_code}]: {res.text}")
        return res.content

    def create_group(self, name: str, member_user_ids: List[int]) -> Dict[str, Any]:
        """Creates a new group with specified members."""
        url = f"{self.base_url}/api/v1/groups"
        payload = {"name": name, "member_user_ids": member_user_ids}
        res = self.session.post(url, json=payload, headers=self._auth_headers())
        if res.status_code not in (200, 201):
            raise RuntimeError(f"create_group failed [{res.status_code}]: {res.text}")
        return res.json()

    def get_groups(self) -> List[Dict[str, Any]]:
        """Lists user's groups."""
        url = f"{self.base_url}/api/v1/groups"
        res = self.session.get(url, headers=self._auth_headers())
        if res.status_code != 200:
            raise RuntimeError(f"get_groups failed [{res.status_code}]: {res.text}")
        data = res.json()
        if isinstance(data, dict):
            return data.get("groups", [])
        return data

    # ── WebSocket ──

    async def connect_ws(self):
        """Opens authenticated binary WebSocket connection."""
        if not self.token:
            raise RuntimeError("Cannot connect WS: client is not authenticated")

        subprotocols = ["access_token", self.token]
        headers = {"Authorization": f"Bearer {self.token}"}

        import inspect
        sig = inspect.signature(websockets.connect)
        connect_kwargs = {
            "subprotocols": subprotocols,
            "ping_interval": None,
        }
        if "additional_headers" in sig.parameters:
            connect_kwargs["additional_headers"] = headers
        else:
            connect_kwargs["extra_headers"] = headers

        self.ws = await websockets.connect(self.ws_url, **connect_kwargs)

    async def close_ws(self):
        """Closes active WebSocket."""
        if self.ws:
            await self.ws.close()
            self.ws = None

    async def send_frame(self, opcode: int, payload: Any):
        """Packs and sends a single binary MsgPack frame: [opcode byte, ...msgpack_bytes]."""
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        payload_bytes = msgpack.packb(payload, use_bin_type=True)
        frame = bytes([opcode]) + payload_bytes
        await self.ws.send(frame)

    async def recv_frame(self, timeout: float = 5.0) -> Tuple[int, Any]:
        """Receives and unmarshals one binary frame: (opcode, payload_dict)."""
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        raw = await asyncio.wait_for(self.ws.recv(), timeout=timeout)
        if isinstance(raw, str):
            raise ValueError("Expected binary frame, received text")
        opcode = raw[0]
        payload = msgpack.unpackb(raw[1:], raw=False)
        return opcode, payload

    async def wait_for_frame(self, expected_opcodes: int | Tuple[int, ...], timeout: float = 5.0) -> Tuple[int, Any]:
        """Waits for a frame matching one of the expected opcodes, ignoring background frames like receipts/ping."""
        if isinstance(expected_opcodes, int):
            expected_opcodes = (expected_opcodes,)

        deadline = time.time() + timeout
        while time.time() < deadline:
            remaining = max(0.1, deadline - time.time())
            opcode, payload = await self.recv_frame(timeout=remaining)
            if opcode in expected_opcodes:
                return opcode, payload
        raise asyncio.TimeoutError(f"Timed out waiting for frame with opcodes {expected_opcodes}")

    def get_shared_secret(self, peer_pub_bytes: bytes) -> bytes:
        """Returns cached or derived X25519 shared secret."""
        if peer_pub_bytes not in self._shared_secrets_cache:
            self._shared_secrets_cache[peer_pub_bytes] = derive_shared_secret(
                self.private_key, peer_pub_bytes
            )
        return self._shared_secrets_cache[peer_pub_bytes]

    async def send_e2ee_direct_message(
        self,
        recipient_user_id: int,
        recipient_device_id: int,
        recipient_pub_bytes: bytes,
        plaintext: str,
        client_msg_id: Optional[str] = None,
        reply_to_msg_id: Optional[str] = None
    ) -> str:
        """
        Encrypts and sends a direct pairwise E2EE message over WebSocket.
        Returns the client message ID.
        """
        if not client_msg_id:
            client_msg_id = str(uuid.uuid4())

        now = int(time.time())
        shared_secret = self.get_shared_secret(recipient_pub_bytes)
        aad = build_pairwise_aad(self.user_id, recipient_user_id, client_msg_id, now)

        enc = e2ee_encrypt(plaintext, shared_secret, aad=aad)

        devices_payload = [
            {
                "device_id": recipient_device_id,
                "ciphertext": enc["ciphertext"],
                "salt": enc["salt"],
                "nonce": enc["nonce"],
            }
        ]

        frame_payload = {
            "to_user_id": recipient_user_id,
            "msg_id": client_msg_id,
            "reply_to_msg_id": reply_to_msg_id,
            "created_at": now,
            "devices": devices_payload,
        }

        await self.send_frame(OP_MSG_SEND, frame_payload)
        return client_msg_id

    async def decrypt_received_message(self, recv_payload: Dict[str, Any], sender_pub_bytes: bytes) -> str:
        """
        Decrypts an incoming OpMsgRecv frame payload using sender's public key.
        Verifies AAD and returns decrypted plaintext string.
        """
        sender_id = recv_payload["from_user_id"]
        client_msg_id = recv_payload["client_msg_id"]
        ts = recv_payload["ts"]
        ciphertext = recv_payload["ciphertext"]
        salt = recv_payload["salt"]
        nonce = recv_payload["nonce"]

        shared_secret = self.get_shared_secret(sender_pub_bytes)
        aad = build_pairwise_aad(sender_id, self.user_id, client_msg_id, ts)

        decrypted_bytes = e2ee_decrypt(ciphertext, shared_secret, salt, nonce, aad=aad)
        return decrypted_bytes.decode("utf-8")

    async def send_delivery_receipt(self, server_msg_id: int, sender_user_id: int):
        """Sends OpMsgDelivered receipt for received message."""
        await self.send_frame(OP_MSG_DELIVERED, {
            "msg_id": server_msg_id,
            "chat_user_id": sender_user_id,
        })

    async def send_read_receipt(self, server_msg_id: int, sender_user_id: int):
        """Sends OpMsgRead receipt for viewed message."""
        await self.send_frame(OP_MSG_READ, {
            "msg_id": server_msg_id,
            "chat_user_id": sender_user_id,
        })
