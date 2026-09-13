#!/usr/bin/env python3
"""
Penik Messenger — End-to-End (E2E) Test Suite.
Tests full multi-client workflows: Auth, REST, WebSocket, X25519 E2EE messaging,
offline queuing, encrypted attachments, safety numbers, and groups.
"""

import os
import sys
import time
import uuid
import base64
import hashlib
import asyncio
import argparse
import socket
import glob
import signal
import subprocess
import tempfile
from pathlib import Path
from typing import List, Dict, Any

import requests
import websockets

from .client import (
    PenikClient,
    OP_MSG_SEND,
    OP_MSG_RECV,
    OP_MSG_ACK,
    OP_MSG_DELIVERED,
    OP_OFFLINE_BATCH,
    OP_MSG_READ,
    OP_MSG_DELETE,
    OP_MSG_DELETE_NOTIFY,
    OP_MSG_EDIT,
    OP_MSG_EDIT_NOTIFY,
    OP_MSG_RETRY_REQ,
    OP_MSG_RETRY_RESP,
    OP_GROUP_MSG_SEND,
    OP_GROUP_MSG_RECV,
    OP_GROUP_MSG_ACK,
    OP_GROUP_MESSAGE_EDIT,
    OP_GROUP_MESSAGE_EDIT_NOTIFY,
)
from .crypto_utils import (
    encrypt_file,
    decrypt_file,
    compute_safety_fingerprint,
    build_pairwise_aad,
    e2ee_encrypt,
)


# Terminal ANSI styling
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"


def log_step(name: str):
    print(f"\n{BOLD}{CYAN}=== [TEST STEP] {name} ==={RESET}")


def log_pass(msg: str):
    print(f"  {GREEN}✔ [PASS]{RESET} {msg}")


def log_fail(msg: str):
    print(f"  {RED}✖ [FAIL]{RESET} {msg}")


def log_info(msg: str):
    print(f"  {YELLOW}ℹ{RESET} {msg}")


class E2ETestSuite:
    def __init__(self, base_url: str, ws_url: str):
        self.base_url = base_url
        self.ws_url = ws_url
        self.alice: PenikClient = PenikClient(base_url, ws_url)
        self.bob: PenikClient = PenikClient(base_url, ws_url)
        self.extra_clients: List[PenikClient] = []
        self.passed_tests = 0
        self.failed_tests = 0

    def assert_true(self, condition: bool, description: str):
        if condition:
            log_pass(description)
            self.passed_tests += 1
        else:
            log_fail(description)
            self.failed_tests += 1
            raise AssertionError(f"Check failed: {description}")

    async def run_all(self):
        start_time = time.time()
        print(f"{BOLD}Starting Penik E2E Test Suite on {self.base_url}...{RESET}")

        try:
            await self.test_registration_and_profiles()
            await self.test_key_bundle_exchange()
            await self.test_live_e2ee_messaging()
            await self.test_edit_and_delete_messaging()
            await self.test_retry_flow()
            await self.test_offline_messaging()
            await self.test_encrypted_file_attachments()
            await self.test_safety_numbers()
            await self.test_group_lifecycle()
            await self.test_group_kick_and_rotation()
            await self.test_limits_and_spoofing()
            await self.test_r1_logout_kills_ws()

            elapsed = time.time() - start_time
            print(f"\n{BOLD}{GREEN}------------------------------------------------------------{RESET}")
            print(f"{BOLD}{GREEN}ALL {self.passed_tests} E2E TESTS PASSED SUCCESSFULLY! ({elapsed:.2f}s){RESET}")
            print(f"{BOLD}{GREEN}------------------------------------------------------------{RESET}")
            return 0
        except Exception as e:
            elapsed = time.time() - start_time
            print(f"\n{BOLD}{RED}------------------------------------------------------------{RESET}")
            print(f"{BOLD}{RED}TEST RUN ABORTED WITH ERROR after {elapsed:.2f}s: {e}{RESET}")
            print(f"Passed: {self.passed_tests}, Failed: {self.failed_tests + 1}")
            print(f"{BOLD}{RED}------------------------------------------------------------{RESET}")
            return 1
        finally:
            await self.cleanup()

    async def test_registration_and_profiles(self):
        log_step("1. User Registration & Profile Verification")

        suffix = uuid.uuid4().hex[:8]
        alice_nick = f"alice_{suffix}"
        bob_nick = f"bob_{suffix}"

        # Register Alice
        alice_data = self.alice.register(
            nickname=alice_nick,
            name="Alice Smith",
            device_name="Alice-Laptop"
        )
        self.assert_true(bool(self.alice.token), "Alice received session token")
        self.assert_true(self.alice.user_id > 0, f"Alice assigned user_id={self.alice.user_id}")
        self.assert_true(self.alice.device_id > 0, f"Alice assigned device_id={self.alice.device_id}")

        # Register Bob
        bob_data = self.bob.register(
            nickname=bob_nick,
            name="Bob Jones",
            device_name="Bob-Phone"
        )
        self.assert_true(bool(self.bob.token), "Bob received session token")
        self.assert_true(self.bob.user_id > 0, f"Bob assigned user_id={self.bob.user_id}")
        self.assert_true(self.bob.device_id > 0, f"Bob assigned device_id={self.bob.device_id}")

        # Verify profile endpoints
        alice_me = self.alice.get_me()
        self.assert_true(alice_me.get("nickname") == alice_nick, "Alice get_me returned correct nickname")

        bob_me = self.bob.get_me()
        self.assert_true(bob_me.get("nickname") == bob_nick, "Bob get_me returned correct nickname")

    async def test_key_bundle_exchange(self):
        log_step("2. Public Key Bundles Discovery")

        # Alice looks up Bob's keys
        bob_bundle = self.alice.get_key_bundle(self.bob.user_id)
        self.assert_true("devices" in bob_bundle and len(bob_bundle["devices"]) > 0, "Alice received Bob's devices")

        bob_device = bob_bundle["devices"][0]
        bob_ik_b64 = bob_device["identity_key"]
        bob_ik_bytes = base64.b64decode(bob_ik_b64)
        self.assert_true(bob_ik_bytes == self.bob.public_key_bytes, "Bob's published identity key matches local public key")

        # Bob looks up Alice's keys
        alice_bundle = self.bob.get_key_bundle(self.alice.user_id)
        self.assert_true("devices" in alice_bundle and len(alice_bundle["devices"]) > 0, "Bob received Alice's devices")

        alice_device = alice_bundle["devices"][0]
        alice_ik_b64 = alice_device["identity_key"]
        alice_ik_bytes = base64.b64decode(alice_ik_b64)
        self.assert_true(alice_ik_bytes == self.alice.public_key_bytes, "Alice's published identity key matches local public key")

    async def test_live_e2ee_messaging(self):
        log_step("3. Live WebSocket Connection & E2EE Direct Messaging")

        # Connect both clients simultaneously
        await self.alice.connect_ws()
        log_pass("Alice connected to WebSocket")
        await self.bob.connect_ws()
        log_pass("Bob connected to WebSocket")

        test_message_text = "Hello Bob! Secret meeting at 14:00. [Penik E2EE Protocol]"
        client_msg_id = str(uuid.uuid4())

        # Alice encrypts and sends message to Bob
        log_info(f"Alice sending encrypted message to Bob (to_user_id={self.bob.user_id})...")
        sent_id = await self.alice.send_e2ee_direct_message(
            recipient_user_id=self.bob.user_id,
            recipient_device_id=self.bob.device_id,
            recipient_pub_bytes=self.bob.public_key_bytes,
            plaintext=test_message_text,
            client_msg_id=client_msg_id
        )

        # Alice should receive OpMsgAck
        ack_opcode, ack_payload = await self.alice.wait_for_frame(OP_MSG_ACK, timeout=5.0)
        self.assert_true(ack_opcode == OP_MSG_ACK, f"Alice received OpMsgAck (opcode=0x{ack_opcode:02x})")
        self.assert_true(ack_payload.get("client_msg_id") == client_msg_id, "Ack client_msg_id matches")
        server_msg_id = ack_payload["msg_id"]

        # Bob should receive OpMsgRecv
        recv_opcode, recv_payload = await self.bob.wait_for_frame(OP_MSG_RECV, timeout=5.0)
        self.assert_true(recv_opcode == OP_MSG_RECV, f"Bob received OpMsgRecv (opcode=0x{recv_opcode:02x})")
        self.assert_true(recv_payload.get("from_user_id") == self.alice.user_id, "Recv from_user_id matches Alice")
        self.assert_true(recv_payload.get("client_msg_id") == client_msg_id, "Recv client_msg_id matches Alice's message")

        # Bob decrypts the message
        decrypted_text = await self.bob.decrypt_received_message(recv_payload, self.alice.public_key_bytes)
        self.assert_true(decrypted_text == test_message_text, f"Bob decrypted message accurately: '{decrypted_text}'")

        # Bob sends delivery and read receipts
        await self.bob.send_delivery_receipt(server_msg_id, self.alice.user_id)
        log_pass("Bob sent delivery receipt")
        await self.bob.send_read_receipt(server_msg_id, self.alice.user_id)
        log_pass("Bob sent read receipt")

        # Alice receives delivery or read receipt
        receipt_opcode, receipt_payload = await self.alice.wait_for_frame((OP_MSG_DELIVERED, OP_MSG_READ), timeout=5.0)
        self.assert_true(receipt_opcode in (OP_MSG_DELIVERED, OP_MSG_READ), f"Alice received receipt frame (0x{receipt_opcode:02x})")

        # Bob replies to Alice
        reply_text = "Message received and verified! Ready for the meeting."
        reply_client_id = str(uuid.uuid4())
        await self.bob.send_e2ee_direct_message(
            recipient_user_id=self.alice.user_id,
            recipient_device_id=self.alice.device_id,
            recipient_pub_bytes=self.alice.public_key_bytes,
            plaintext=reply_text,
            client_msg_id=reply_client_id,
            reply_to_msg_id=client_msg_id
        )

        # Bob receives Ack
        await self.bob.wait_for_frame(OP_MSG_ACK, timeout=5.0)

        # Alice receives Bob's reply
        reply_recv_op, reply_recv_payload = await self.alice.wait_for_frame(OP_MSG_RECV, timeout=5.0)
        self.assert_true(reply_recv_op == OP_MSG_RECV, f"Alice received reply OpMsgRecv (0x{reply_recv_op:02x})")
        alice_decrypted = await self.alice.decrypt_received_message(reply_recv_payload, self.bob.public_key_bytes)
        self.assert_true(alice_decrypted == reply_text, f"Alice decrypted Bob's reply: '{alice_decrypted}'")

    async def test_edit_and_delete_messaging(self):
        log_step("4. Message Edit & Delete for Everyone (0x0d / 0x0a)")

        # Alice sends initial message to Bob
        orig_text = "Message to be edited and deleted"
        client_msg_id = str(uuid.uuid4())
        await self.alice.send_e2ee_direct_message(
            recipient_user_id=self.bob.user_id,
            recipient_device_id=self.bob.device_id,
            recipient_pub_bytes=self.bob.public_key_bytes,
            plaintext=orig_text,
            client_msg_id=client_msg_id
        )

        # Alice gets ACK
        await self.alice.wait_for_frame(OP_MSG_ACK, timeout=5.0)

        # Bob receives initial message
        _, recv_payload = await self.bob.wait_for_frame(OP_MSG_RECV, timeout=5.0)
        bob_decrypted = await self.bob.decrypt_received_message(recv_payload, self.alice.public_key_bytes)
        self.assert_true(bob_decrypted == orig_text, "Bob decrypted original message before edit")

        # Alice edits the message
        edited_text = "Message successfully updated! [EDITED]"
        await self.alice.edit_e2ee_direct_message(
            recipient_user_id=self.bob.user_id,
            recipient_device_id=self.bob.device_id,
            recipient_pub_bytes=self.bob.public_key_bytes,
            client_msg_id=client_msg_id,
            new_plaintext=edited_text
        )

        # Bob receives OP_MSG_EDIT_NOTIFY (0x0e)
        edit_op, edit_payload = await self.bob.wait_for_frame(OP_MSG_EDIT_NOTIFY, timeout=5.0)
        self.assert_true(edit_op == OP_MSG_EDIT_NOTIFY, "Bob received OpMsgEditNotify (0x0e)")
        self.assert_true(edit_payload.get("client_msg_id") == client_msg_id, "Edit notification matches client_msg_id")

        bob_decrypted_edit = await self.bob.decrypt_received_message(edit_payload, self.alice.public_key_bytes)
        self.assert_true(bob_decrypted_edit == edited_text, f"Bob decrypted edited message: '{bob_decrypted_edit}'")

        # Alice deletes the message for everyone
        await self.alice.delete_message(
            client_msg_id=client_msg_id,
            chat_id=self.bob.user_id,
            delete_for_everyone=True
        )

        # Bob receives OP_MSG_DELETE_NOTIFY (0x0b)
        del_op, del_payload = await self.bob.wait_for_frame(OP_MSG_DELETE_NOTIFY, timeout=5.0)
        self.assert_true(del_op == OP_MSG_DELETE_NOTIFY, "Bob received OpMsgDeleteNotify (0x0b)")
        self.assert_true(del_payload.get("msg_id") == client_msg_id, "Delete notification matches msg_id")
        self.assert_true(bool(del_payload.get("delete_for_everyone")), "Delete notification has delete_for_everyone=True")

    async def test_retry_flow(self):
        log_step("5. Pairwise Key/Decryption Retry Flow (0x16 MsgRetryReq -> 0x17 MsgRetryResp)")

        # Alice sends a message to Bob
        msg_text = "Important message testing retry flow"
        client_msg_id = str(uuid.uuid4())
        await self.alice.send_e2ee_direct_message(
            recipient_user_id=self.bob.user_id,
            recipient_device_id=self.bob.device_id,
            recipient_pub_bytes=self.bob.public_key_bytes,
            plaintext=msg_text,
            client_msg_id=client_msg_id
        )

        ack_op, ack_payload = await self.alice.wait_for_frame(OP_MSG_ACK, timeout=5.0)
        server_msg_id = ack_payload["msg_id"]

        # Bob receives the initial message
        recv_op, recv_payload = await self.bob.wait_for_frame(OP_MSG_RECV, timeout=5.0)

        # Bob sends OpMsgRetryReq asking Alice's device to re-encrypt
        await self.bob.request_message_retry(
            sender_device_id=self.alice.device_id,
            server_msg_id=server_msg_id
        )

        # Alice receives OpMsgRetryReq on her WS
        retry_req_op, retry_req_payload = await self.alice.wait_for_frame(OP_MSG_RETRY_REQ, timeout=5.0)
        self.assert_true(retry_req_op == OP_MSG_RETRY_REQ, "Alice received OpMsgRetryReq (0x16)")
        self.assert_true(retry_req_payload.get("msg_id") == server_msg_id, "Retry request matches server_msg_id")

        # Alice re-encrypts the plaintext with fresh salt/nonce and responds with OpMsgRetryResp
        shared_secret = self.alice.get_shared_secret(self.bob.public_key_bytes)
        re_ts = recv_payload.get("ts", 0)
        re_aad = build_pairwise_aad(self.alice.user_id, self.bob.user_id, client_msg_id, re_ts)
        re_enc = e2ee_encrypt(msg_text, shared_secret, aad=re_aad)

        await self.alice.send_message_retry_resp(
            server_msg_id=server_msg_id,
            ciphertext=re_enc["ciphertext"],
            salt=re_enc["salt"],
            nonce=re_enc["nonce"]
        )

        # Bob receives the re-delivered OpMsgRecv
        re_recv_op, re_recv_payload = await self.bob.wait_for_frame(OP_MSG_RECV, timeout=5.0)
        self.assert_true(re_recv_op == OP_MSG_RECV, "Bob received re-encrypted OpMsgRecv")
        self.assert_true(re_recv_payload.get("msg_id") == server_msg_id, "Re-delivered message matches server_msg_id")

        bob_decrypted = await self.bob.decrypt_received_message(re_recv_payload, self.alice.public_key_bytes)
        self.assert_true(bob_decrypted == msg_text, f"Bob decrypted retry message successfully: '{bob_decrypted}'")

    async def test_offline_messaging(self):
        log_step("6. Offline Message Queuing & Reconnect Batch Delivery")

        # Bob goes offline
        log_info("Bob is disconnecting from WebSocket...")
        await self.bob.close_ws()
        log_pass("Bob disconnected")

        # Alice sends an offline message
        offline_text = "Bob, check this when you come back online!"
        offline_id = str(uuid.uuid4())
        await self.alice.send_e2ee_direct_message(
            recipient_user_id=self.bob.user_id,
            recipient_device_id=self.bob.device_id,
            recipient_pub_bytes=self.bob.public_key_bytes,
            plaintext=offline_text,
            client_msg_id=offline_id
        )

        # Alice gets Ack
        ack_op, _ = await self.alice.wait_for_frame(OP_MSG_ACK, timeout=5.0)
        self.assert_true(ack_op == OP_MSG_ACK, "Alice received Ack for offline message")

        # Bob reconnects
        log_info("Bob reconnecting to WebSocket...")
        await self.bob.connect_ws()
        log_pass("Bob reconnected")

        # Bob should receive offline messages
        queued_found = False
        for _ in range(5):
            try:
                op, payload = await self.bob.recv_frame(timeout=4.0)
                if op == OP_OFFLINE_BATCH:
                    messages = payload.get("msgs", []) or payload.get("messages", [])
                    for m in messages:
                        if m.get("client_msg_id") == offline_id:
                            decrypted = await self.bob.decrypt_received_message(m, self.alice.public_key_bytes)
                            self.assert_true(decrypted == offline_text, "Bob decrypted offline batch message")
                            queued_found = True
                            break
                    if queued_found:
                        break
                elif op == OP_MSG_RECV:
                    if payload.get("client_msg_id") == offline_id:
                        decrypted = await self.bob.decrypt_received_message(payload, self.alice.public_key_bytes)
                        self.assert_true(decrypted == offline_text, "Bob decrypted offline direct message")
                        queued_found = True
                        break
            except asyncio.TimeoutError:
                break

        self.assert_true(queued_found, "Bob received and decrypted the queued offline message")

    async def test_encrypted_file_attachments(self):
        log_step("7. Encrypted File Attachments (ChaCha20-Poly1305 + Upload + Download)")

        # Generate sample binary payload (e.g., simulated image or document)
        sample_file_data = b"PENIK_TEST_FILE_CONTENT_" + os.urandom(16 * 1024)
        original_hash = hashlib.sha256(sample_file_data).hexdigest()

        # Alice encrypts file client-side
        encrypted_bytes, file_key = encrypt_file(sample_file_data)
        log_info(f"Alice encrypted file: original {len(sample_file_data)} bytes -> encrypted {len(encrypted_bytes)} bytes")

        # Alice uploads encrypted file to server
        upload_resp = self.alice.upload_attachment(encrypted_bytes, filename="document.pdf.bin")
        attach_id = upload_resp["id"]
        self.assert_true(bool(attach_id), f"File uploaded successfully, attach_id={attach_id}")

        # Bob downloads the file (has chat relation with Alice from previous steps)
        downloaded_ciphertext = self.bob.download_attachment(attach_id)
        self.assert_true(downloaded_ciphertext == encrypted_bytes, "Bob downloaded identical encrypted bytes from server")

        # Bob decrypts the file with the symmetric file key
        bob_decrypted_data = decrypt_file(downloaded_ciphertext, file_key)
        bob_hash = hashlib.sha256(bob_decrypted_data).hexdigest()

        self.assert_true(bob_hash == original_hash, f"Decrypted file SHA-256 matches perfectly ({bob_hash[:16]}...)")

    async def test_safety_numbers(self):
        log_step("8. Safety Number Verification (Fingerprint Parity)")

        # Alice computes fingerprint for (Alice, Bob)
        alice_view = compute_safety_fingerprint(
            [self.alice.public_key_bytes],
            [self.bob.public_key_bytes],
            user_id=self.alice.user_id
        )

        # Bob computes fingerprint for (Bob, Alice)
        bob_view = compute_safety_fingerprint(
            [self.bob.public_key_bytes],
            [self.alice.public_key_bytes],
            user_id=self.bob.user_id
        )

        self.assert_true(alice_view["hex"] == bob_view["hex"], f"Hex fingerprints match: {alice_view['hex'][:16]}...")
        self.assert_true(alice_view["number"] == bob_view["number"], f"Numeric blocks match: {alice_view['number']}")
        self.assert_true(alice_view["qr_payload"].startswith(f"penik://safety?fp={alice_view['hex']}&uid={self.alice.user_id}"), "Alice QR payload contains fp and uid")
        self.assert_true(bob_view["qr_payload"].startswith(f"penik://safety?fp={bob_view['hex']}&uid={self.bob.user_id}"), "Bob QR payload contains fp and uid")

    async def test_group_lifecycle(self):
        log_step("9. Group Chat Creation & Membership Verification")

        group_name = "Penik Security Team"
        create_res = self.alice.create_group(name=group_name, member_user_ids=[self.bob.user_id])
        group_id = create_res.get("id") or create_res.get("group_id")
        self.assert_true(bool(group_id), f"Group created with id={group_id}")

        alice_groups = self.alice.get_groups()
        alice_has_group = any(g.get("name") == group_name for g in alice_groups)
        self.assert_true(alice_has_group, "Alice lists the created group")

        bob_groups = self.bob.get_groups()
        bob_has_group = any(g.get("name") == group_name for g in bob_groups)
        self.assert_true(bob_has_group, "Bob lists the created group as member")

    async def test_group_kick_and_rotation(self):
        log_step("10. Group Member Kick & Epoch Rotation (R5 Security)")

        group_name = "Security Audit Group"
        create_res = self.alice.create_group(name=group_name, member_user_ids=[self.bob.user_id])
        group_id = create_res.get("id") or create_res.get("group_id")
        self.assert_true(bool(group_id), f"Created group with id={group_id}")

        # Bob accepts invitation to become active member
        accept_res = self.bob.accept_group_invitation(group_id)
        self.assert_true(accept_res.status_code in (200, 204), f"Bob accepted group invitation [{accept_res.status_code}]")

        # Bob sends a message in the group
        group_msg_id = str(uuid.uuid4())
        sample_ct = b"sample_group_ciphertext"
        sample_salt = b"salt_16_bytes___"
        sample_nonce = b"nonce_12_byt"

        await self.bob.send_group_message(
            group_id=group_id,
            message_id=group_msg_id,
            key_version=1,
            ciphertext=sample_ct,
            salt=sample_salt,
            nonce=sample_nonce
        )

        # Bob receives ACK
        ack_op, ack_payload = await self.bob.wait_for_frame(OP_GROUP_MSG_ACK, timeout=5.0)
        self.assert_true(ack_op == OP_GROUP_MSG_ACK, "Bob received OpGroupMessageAck (0x22)")
        self.assert_true(ack_payload.get("message_id") == group_msg_id, "Group Ack message_id matches")

        # Alice receives Bob's group message
        recv_op, recv_payload = await self.alice.wait_for_frame(OP_GROUP_MSG_RECV, timeout=5.0)
        self.assert_true(recv_op == OP_GROUP_MSG_RECV, "Alice received OpGroupMessageRecv (0x21)")
        self.assert_true(recv_payload.get("message_id") == group_msg_id, "Group message_id matches")

        # Alice kicks Bob from group
        kick_res = self.alice.remove_group_member(group_id, self.bob.user_id)
        self.assert_true(kick_res.status_code == 204, f"Alice kicked Bob from group [status={kick_res.status_code}]")

        # Alice rotates group key
        rotate_res = self.alice.rotate_group_key(group_id)
        self.assert_true(rotate_res.get("key_version") == 2, f"Group key rotated to version {rotate_res.get('key_version')}")
        active_devices = rotate_res.get("devices", [])
        bob_in_rotation = any(d.get("user_id") == self.bob.user_id for d in active_devices)
        self.assert_true(not bob_in_rotation, "Kicked member (Bob) excluded from new group epoch recipients")

        # Kicked Bob attempts to edit his previous message
        await self.bob.edit_group_message(
            group_id=group_id,
            message_id=group_msg_id,
            key_version=1,
            ciphertext=b"unauthorized_edit_ct",
            salt=sample_salt,
            nonce=sample_nonce
        )

        # Verify Alice does NOT receive any edit notification from kicked Bob
        alice_got_edit = False
        try:
            op, _ = await self.alice.recv_frame(timeout=1.0)
            if op == OP_GROUP_MESSAGE_EDIT_NOTIFY:
                alice_got_edit = True
        except asyncio.TimeoutError:
            pass
        self.assert_true(not alice_got_edit, "Server rejected group message edit from kicked member (R5 verified)")

    async def test_limits_and_spoofing(self):
        log_step("11. Limits & Third-Party Device Spoofing Prevention (R4 Security)")

        # 1. REST limit: ciphertext > 128 KiB
        oversized_ct = base64.b64encode(b"X" * (129 * 1024)).decode("ascii")
        dummy_salt = base64.b64encode(b"S" * 16).decode("ascii")
        dummy_nonce = base64.b64encode(b"N" * 12).decode("ascii")

        oversized_payload = [
            {
                "device_id": self.bob.device_id,
                "ciphertext": oversized_ct,
                "salt": dummy_salt,
                "nonce": dummy_nonce,
            }
        ]
        res_ct = self.alice.send_message_rest(
            to_user_id=self.bob.user_id,
            msg_id=str(uuid.uuid4()),
            devices=oversized_payload
        )
        self.assert_true(res_ct.status_code == 400, f"REST rejected >128 KiB ciphertext [status={res_ct.status_code}]")

        # 2. REST limit: > 50 devices
        fifty_one_devices = [
            {
                "device_id": self.bob.device_id + i,
                "ciphertext": base64.b64encode(b"hello").decode("ascii"),
                "salt": dummy_salt,
                "nonce": dummy_nonce,
            }
            for i in range(51)
        ]
        res_dev = self.alice.send_message_rest(
            to_user_id=self.bob.user_id,
            msg_id=str(uuid.uuid4()),
            devices=fifty_one_devices
        )
        self.assert_true(res_dev.status_code == 400, f"REST rejected >50 devices [status={res_dev.status_code}]")

        # 3. WS limit: ciphertext > 128 KiB
        oversized_raw = b"W" * (129 * 1024)
        oversized_frame = {
            "to_user_id": self.bob.user_id,
            "msg_id": str(uuid.uuid4()),
            "devices": [
                {
                    "device_id": self.bob.device_id,
                    "ciphertext": oversized_raw,
                    "salt": b"S" * 16,
                    "nonce": b"N" * 12,
                }
            ]
        }
        await self.alice.send_frame(OP_MSG_SEND, oversized_frame)
        ws_rejected = False
        try:
            await self.alice.recv_frame(timeout=0.8)
        except asyncio.TimeoutError:
            ws_rejected = True
        self.assert_true(ws_rejected, "WS dropped frame with >128 KiB ciphertext (no ACK)")

        # 4. Third-party device ID spoofing
        charlie = PenikClient(self.base_url, self.ws_url)
        self.extra_clients.append(charlie)
        charlie_nick = f"charlie_{uuid.uuid4().hex[:8]}"
        charlie.register(nickname=charlie_nick, name="Charlie Brown", device_name="Charlie-Phone")
        await charlie.connect_ws()

        spoofed_devices = [
            {
                "device_id": self.bob.device_id,
                "ciphertext": base64.b64encode(b"for_bob").decode("ascii"),
                "salt": dummy_salt,
                "nonce": dummy_nonce,
            },
            {
                "device_id": charlie.device_id,
                "ciphertext": base64.b64encode(b"spoofed_for_charlie").decode("ascii"),
                "salt": dummy_salt,
                "nonce": dummy_nonce,
            }
        ]
        spoofed_msg_id = str(uuid.uuid4())
        res_spoof = self.alice.send_message_rest(
            to_user_id=self.bob.user_id,
            msg_id=spoofed_msg_id,
            devices=spoofed_devices
        )
        self.assert_true(res_spoof.status_code in (200, 201), f"Message sent to Bob with stranger device [status={res_spoof.status_code}]")

        bob_got_msg = False
        try:
            _, p = await self.bob.wait_for_frame(OP_MSG_RECV, timeout=3.0)
            if p.get("client_msg_id") == spoofed_msg_id:
                bob_got_msg = True
        except asyncio.TimeoutError:
            pass
        self.assert_true(bob_got_msg, "Bob received legitimate message")

        charlie_got_leak = False
        try:
            await charlie.recv_frame(timeout=1.0)
            charlie_got_leak = True
        except asyncio.TimeoutError:
            pass
        self.assert_true(not charlie_got_leak, "Stranger device (Charlie) did not receive leaked ciphertext (R4 verified)")

    async def test_r1_logout_kills_ws(self):
        log_step("12. Session Logout Terminates Active WebSocket (R1 Security)")

        eve = PenikClient(self.base_url, self.ws_url)
        self.extra_clients.append(eve)
        eve_nick = f"eve_{uuid.uuid4().hex[:8]}"
        eve.register(nickname=eve_nick, name="Eve Auditor", device_name="Eve-Device")
        await eve.connect_ws()
        self.assert_true(eve.ws is not None and getattr(eve.ws.state, "name", "") == "OPEN", "Eve connected to WebSocket")

        logout_res = eve.logout()
        self.assert_true(logout_res.status_code == 204, f"Eve logout succeeded [status={logout_res.status_code}]")

        ws_closed = False
        close_code = None
        try:
            # Active read triggers the websockets state machine to process incoming server close frame
            await eve.recv_frame(timeout=3.0)
        except websockets.exceptions.ConnectionClosed as e:
            ws_closed = True
            close_code = getattr(e.rcvd, "code", None) or getattr(eve.ws, "close_code", None)
        except Exception:
            pass

        if not ws_closed:
            try:
                await asyncio.wait_for(eve.ws.wait_closed(), timeout=2.0)
                ws_closed = (getattr(eve.ws.state, "name", "") == "CLOSED")
                close_code = getattr(eve.ws, "close_code", None)
            except Exception:
                ws_closed = (getattr(eve.ws.state, "name", "") == "CLOSED")
                close_code = getattr(eve.ws, "close_code", None)

        self.assert_true(ws_closed, "WebSocket terminated immediately upon REST logout")
        self.assert_true(close_code == 1008, f"WebSocket closed with PolicyViolation code 1008 (got {close_code})")

        try:
            eve.get_me()
            token_rejected = False
        except RuntimeError:
            token_rejected = True
        self.assert_true(token_rejected, "Revoked session token cannot access REST endpoints (401)")

    async def cleanup(self):
        log_info("Cleaning up WebSocket connections...")
        for c in [self.alice, self.bob] + self.extra_clients:
            try:
                await c.close_ws()
            except Exception:
                pass


def _is_port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("127.0.0.1", port)) == 0


def _wait_for_server(url: str, timeout: float = 6.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = requests.get(f"{url}/api/v1/users/search", timeout=0.5)
            if r.status_code in (200, 401):
                return True
        except requests.RequestException:
            pass
        time.sleep(0.1)
    return False


def _cleanup_db(db_path: str):
    for f in glob.glob(f"{db_path}*"):
        try:
            os.remove(f)
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser(description="Penik E2E Test Suite")
    parser.add_argument("--url", default=None, help="Base HTTP URL of Penik server (if omitted, auto-starts ephemeral server)")
    parser.add_argument("--ws-url", default=None, help="Base WebSocket URL of Penik server")
    parser.add_argument("--temp-server", "--ephemeral", action="store_true", help="Always launch an isolated ephemeral server with temporary database")
    parser.add_argument("--port", type=int, default=8145, help="Port for auto-started test server (default: 8145)")
    parser.add_argument("--db-path", default=None, help="Custom DB path for ephemeral test server")
    parser.add_argument("--no-build", action="store_true", help="Skip compiling penik-server if it already exists")
    args = parser.parse_args()

    server_proc = None
    test_db_path = None

    if args.url and not args.temp_server:
        base_url = args.url.rstrip("/")
    else:
        # Check if default port 8143 already has a running and healthy server (unless temp-server requested)
        if not args.temp_server and not args.db_path and _is_port_in_use(8143) and _wait_for_server("http://localhost:8143", timeout=1.0):
            base_url = "http://localhost:8143"
        else:
            # Auto-start ephemeral server with temporary database
            repo_root = Path(__file__).resolve().parent.parent.parent
            binary_path = repo_root / "penik-server"
            if not binary_path.exists() or not args.no_build:
                dist_dir = repo_root / "server" / "cmd" / "server" / "dist"
                dist_dir.mkdir(parents=True, exist_ok=True)
                stub_index = dist_dir / "index.html"
                if not stub_index.exists():
                    stub_index.write_text("<!DOCTYPE html><html><body>Penik Headless Test</body></html>\n")

                print(f"{YELLOW}[build] Compiling ./penik-server binary...{RESET}")
                res = subprocess.run(
                    ["go", "build", "-o", str(binary_path), "cmd/server/main.go"],
                    cwd=str(repo_root / "server"),
                    capture_output=True,
                    text=True,
                )
                if res.returncode != 0:
                    print(f"{RED}[build failed]\n{res.stderr}{RESET}")
                    sys.exit(1)
                print(f"{GREEN}[build] ./penik-server ready.{RESET}")

            test_port = args.port
            if _is_port_in_use(test_port):
                test_port = 8146

            test_db_path = args.db_path or str(Path(tempfile.gettempdir()) / f"penik_test_{os.getpid()}_{uuid.uuid4().hex[:6]}.db")
            _cleanup_db(test_db_path)

            base_url = f"http://127.0.0.1:{test_port}"
            env = os.environ.copy()
            env["PENIK_SQLITE_PATH"] = test_db_path
            env["PORT"] = str(test_port)
            env["ENV"] = "development"
            env["ALLOWED_ORIGINS"] = f"http://localhost:{test_port},http://127.0.0.1:{test_port},https://web.penik.ru,https://penik.ru"
            env["LIVEKIT_URL"] = env.get("LIVEKIT_URL", "wss://test-livekit.local")
            env["LIVEKIT_FALLBACK_URL"] = env.get("LIVEKIT_FALLBACK_URL", "wss://test-livekit-fallback.local")

            print(f"{CYAN}[server] Starting ephemeral test server on {base_url} (DB: {test_db_path})...{RESET}")
            server_log = tempfile.NamedTemporaryFile(mode="w+", delete=False, prefix="penik_runner_", suffix=".log")
            server_proc = subprocess.Popen(
                [str(binary_path)],
                cwd=str(repo_root),
                env=env,
                stdout=server_log,
                stderr=server_log,
            )

            if not _wait_for_server(base_url):
                print(f"{RED}[error] Ephemeral test server failed to start on {base_url}{RESET}")
                if server_proc:
                    server_proc.kill()
                try:
                    server_log.seek(0)
                    err_output = server_log.read()
                    if err_output:
                        print(f"{RED}[server output]\n{err_output}{RESET}")
                    os.remove(server_log.name)
                except Exception:
                    pass
                _cleanup_db(test_db_path)
                sys.exit(1)
            print(f"{GREEN}[server] Test server is healthy and responding.{RESET}")

    if args.ws_url:
        ws_url = args.ws_url
    else:
        ws_scheme = "wss" if base_url.startswith("https") else "ws"
        host = base_url.split("://", 1)[1]
        ws_url = f"{ws_scheme}://{host}/api/v1/ws"

    try:
        suite = E2ETestSuite(base_url=base_url, ws_url=ws_url)
        exit_code = asyncio.run(suite.run_all())
    finally:
        if server_proc:
            print(f"\n{YELLOW}[server] Stopping ephemeral test server...{RESET}")
            server_proc.send_signal(signal.SIGTERM)
            try:
                server_proc.wait(timeout=3.0)
            except subprocess.TimeoutExpired:
                server_proc.kill()
            if test_db_path:
                _cleanup_db(test_db_path)
            print(f"{GREEN}[server] Ephemeral test database cleaned up.{RESET}")

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
