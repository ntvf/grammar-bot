# Rewryt — grammar & translation bot

![Build & Release](https://github.com/ntvf/grammar-bot/actions/workflows/release.yml/badge.svg)
![CodeQL](https://github.com/ntvf/grammar-bot/actions/workflows/codeql.yml/badge.svg)
[![Coverage](https://codecov.io/gh/ntvf/grammar-bot/branch/main/graph/badge.svg)](https://codecov.io/gh/ntvf/grammar-bot)

[@RewrytBot](https://t.me/RewrytBot) is a Telegram bot that polishes and translates your messages before you send them. Write something — get it
back corrected, or translated into the language you need, keeping your own voice. One tap for another
version, a different style, another language, or an explanation of what changed.

<p align="center"><img src="docs/demo.gif" width="360" alt="Demo: a message is corrected, made formal, and a Ukrainian message is translated to English"></p>

## How it works for the user

**First run** (`/start`) — a 14-second demo clip, then two taps:
1. *What should I do with your messages?* — ✨ **Smart** (fix if it's already in the target language, otherwise translate) · ✍️ **Fix only** · 🌐 **Translate**
2. *Which language should the result be in?* (skipped for *Fix only*)

…and a **🧪 Try an example** button that runs a sentence with typical mistakes in the user's own language.
Users who skip onboarding and just type still get sensible defaults (Smart → English).

**Every result** is sent as a reply to the original, containing *only* the resulting text (so copy/forward
gives exactly what you want to send), with buttons:

| | |
|---|---|
| 🔄 Another version | Re-words the result (higher temperature, told to differ from the current one) |
| 📋 Copy | One-tap copy (Telegram `copy_text`, shown for results ≤ 256 chars) |
| 🎩 Formal · 😎 Casual · ✂️ Shorter | One-off restyle; active style is marked ✓, tap again to revert |
| 🌐 Language · 🇬🇧 | Re-render this text in any of 21 languages |
| 💡 What changed | Expands up to 5 fixes: ~~original~~ → **fix** — *why* (in the user's interface language) |

While the model works, only the tapped button turns into *⏳ Working on it…* — nothing jumps around.

**Also:**
- **Inline mode** — type `@your_bot some text` in *any* chat and send the fixed version without switching chats. Queries are debounced server-side so only the text the user paused on hits the model.
- **Edit your message** → the bot updates its answer in place.
- Photo **captions** and forwarded messages work too.
- `/settings` — mode, result language, default style (Natural, Formal, Casual, Friendly, Business, Shorter), auto-explanations, interface language. Everything edits one message in place.
- `/language` — quick result-language switch. `/help` — how-to with the demo clip.
- Interface in 10 languages (en, uk, ru, de, es, fr, it, pl, pt, tr), incl. localized command menu and the "What can this bot do?" description.
- A failed model call shows **🔄 Try again**, which fills in the same message on success.

## Tech stack

- Java 25 + Spring Boot 4.1, virtual threads
- PostgreSQL + Liquibase migrations (Hibernate `ddl-auto: validate`)
- Spring AI 2.0 (OpenAI, JSON-schema structured output)
- Telegram Bots 10.3 (long polling)

Updates are processed concurrently across chats but strictly in order within a chat (`ChatTaskDispatcher`),
so one slow model call never blocks other users. Model calls run outside DB transactions.

## Quality gates

Every `./mvnw verify` (CI on PRs and on `main`):
- **Tests** — 120+ unit and integration tests. Integration tests run the real Spring context against
  PostgreSQL via Testcontainers with the real Liquibase schema; only Telegram and the model are mocked, and
  whole conversations (onboarding, buttons, settings, edits, inline mode, limits, failures) are driven through
  the bot. Requires Docker.
- **Coverage** — JaCoCo gate: **85% line / 75% branch** (entities, repositories and config excluded).
  Per-class report in each [workflow run summary](https://github.com/ntvf/grammar-bot/actions/workflows/release.yml) and on Codecov.
- **Translations** — `I18nTest` fails the build if any language is missing a key, a `{0}` placeholder or an
  HTML tag, or exceeds Telegram's length limits.
- **Checkstyle** — unused/redundant/star imports, `==` on strings, boolean simplification, switch fall-through, etc.
- **OWASP Dependency Check** — fails on CVSS ≥ 7 (add the `NVD_API_KEY` secret to speed up NVD updates).
- **CodeQL** — on every push to `main` and weekly.

## Running locally

```bash
docker compose up -d          # PostgreSQL on :5432
export TELEGRAM_BOT_TOKEN=... TELEGRAM_BOT_USERNAME=... OPENAI_API_KEY=...
./mvnw spring-boot:run
```

In **@BotFather**, enable inline mode for the bot: `/setinline` → placeholder e.g. `Type text to fix…`.
Commands and descriptions are registered by the bot itself on startup.

## Deployment (private server)

Each push to `main` publishes a [GitHub Release](https://github.com/ntvf/grammar-bot/releases) with the full
JAR, a thin app-layer JAR (~100 KB) and a checksum of the library layer.

One-time setup:
```bash
sudo useradd --system --home /opt/grammar-bot grammar
sudo -u postgres createuser grammar -P && sudo -u postgres createdb -O grammar grammar
sudo mkdir -p /opt/grammar-bot /etc/grammar-bot && sudo chown grammar: /opt/grammar-bot
sudo cp deploy/grammar-bot.env.example /etc/grammar-bot/grammar-bot.env   # fill in, chmod 600
sudo cp deploy/grammar-bot.service /etc/systemd/system/ && sudo systemctl enable grammar-bot
sudo cp deploy/update.sh /opt/grammar-bot/ && sudo -u grammar /opt/grammar-bot/update.sh
```

`deploy/update.sh` (run it from cron) installs the latest release only when it's new, downloads the
~130 MB library layer only when its checksum changed, keeps the previous version in
`/opt/grammar-bot/previous` for rollback, and restarts the service. The service user needs
`sudo systemctl restart grammar-bot` rights (or run the script as root). Set `REPO` / `GITHUB_TOKEN` if the
repository is private.

Requires Java 25 and PostgreSQL 14+ on the server. Migrations run automatically on start.

### Environment variables

| Variable | Description |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather |
| `TELEGRAM_BOT_USERNAME` | Bot username (without @) |
| `OPENAI_API_KEY` | OpenAI API key |
| `OPENAI_MODEL` | Model (default: `gpt-5.4-mini`) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | PostgreSQL (defaults: `localhost` / `5432` / `grammar` / `grammar` / `grammar`) |
| `ADMIN_CHAT_IDS` | Comma-separated chat ids allowed to use `/stats`; not rate-limited |
| `DAILY_LIMIT` | AI requests per user per day (default: 150) |
| `LOG_AI_CONTENT` | Store texts of AI calls for quality analysis (default: `true`; `false` keeps only metadata) |

### Privacy and retention

Texts are stored only so the buttons under a result keep working; they, the AI-call log and usage counters
are purged after 30 days (`app.retention-days`). When a user blocks the bot, their data is deleted immediately.

## Demo clip

`src/main/resources/onboarding/demo.mp4` and `docs/demo.gif` are rendered by
`tools/demo/generate_demo.py` (Pillow + ffmpeg) — regenerate them if the result keyboard changes.
