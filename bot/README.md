# Penik E2EE Bot Prototype

A standalone Python bot prototype for Penik Messenger featuring end-to-end encryption (E2EE) powered by the Rust `penik-crypto` micro-core and real-time binary WebSocket transport (MsgPack).

## Features

- **End-to-End Encryption (E2EE):** Direct pairwise encryption using X25519 DH key exchange and ***REDACTED-BY-FILTER-REPO*** with AAD verification.
- **Binary WebSocket Transport:** Connects directly via MsgPack framing (`OP_MSG_SEND`, `OP_MSG_RECV`, `OP_MSG_DELIVERED`, `OP_MSG_READ`).
- **Persistent Key Identity:** Identity keypair is saved to `bot_identity.json` on first run to maintain consistent Safety Numbers across restarts.
- **Command Handling:** Built-in support for `/start`, `/ping`, `/info`, `/help` and auto-reply echo.

## Requirements

- Python 3.10+
- `requests`, `msgpack`, `websockets`
- Compiled Rust crypto core (`rust/penik-crypto/target/release/libpenik_crypto.so`)

## Usage

### 1. Create a Bot Account

You can create a bot using your existing Penik user session token:

```bash
python3 bot/bot.py --server http://localhost:8143 --create --user-token <YOUR_USER_TOKEN> --name "Echo Bot" --nickname "echo_bot"
```

This will print the generated API token (`bot_...`).

### 2. Run the Bot

Run the bot using its API token:

```bash
python3 bot/bot.py --server http://localhost:8143 --token <BOT_TOKEN>
```

Or via environment variables:

```bash
export PENIK_SERVER_URL="http://localhost:8143"
export PENIK_BOT_TOKEN="bot_..."
python3 bot/bot.py
```
