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

### 2. Run the Echo Bot

Run the echo bot using its API token:

```bash
python3 bot/bot.py --server http://localhost:8143 --token <BOT_TOKEN>
```

### 3. Run the AI Bot (DeepSeek / OpenAI API)

Run the intelligent E2EE AI bot by passing the bot token and the AI API key directly via command line arguments or environment variables:

```bash
python3 bot/ai_bot.py \
  --server http://localhost:8143 \
  --token <BOT_TOKEN> \
  --key <OPENAI_OR_DEEPSEEK_API_KEY> \
  --base-url https://plusvibeapi.ru/v1 \
  --model deepseek-v4.1-flash \
  --searxng-url https://search.home.slavchat.ru/search
```

#### CLI Arguments:
- `--token`, `-t`: Bot API token (`bot_...`)
- `--key`, `--api-key`, `--openai-api-key`, `-k`: AI provider API key (or `OPENAI_API_KEY` env)
- `--base-url`, `--openai-base-url`: OpenAI-compatible API base URL (default: `https://plusvibeapi.ru/v1`)
- `--model`, `-m`: Model name (default: `deepseek-v4.1-flash`)
- `--server`, `-s`: Penik server URL (default: `http://localhost:8143`)
- `--searxng-url`: SearXNG search endpoint for live web searches (default: `https://search.home.slavchat.ru/search`)
- `--identity`, `-i`: Identity key storage path (default: `ai_bot_identity.json`)

