# Rewryt — grammar & translation bot

![Build & Release](https://github.com/ntvf/grammar-bot/actions/workflows/release.yml/badge.svg)
![CodeQL](https://github.com/ntvf/grammar-bot/actions/workflows/codeql.yml/badge.svg)
[![Coverage](https://codecov.io/gh/ntvf/grammar-bot/branch/main/graph/badge.svg)](https://codecov.io/gh/ntvf/grammar-bot)

[@RewrytBot](https://t.me/RewrytBot) is a Telegram bot that polishes and translates your messages before you send them. Write something — get it
back corrected, or translated into the language you need, keeping your own voice. One tap for another
language, a different style, a shorter version, or an explanation of what changed.

<p align="center"><img src="docs/demo.gif" width="360" alt="Demo: a message is corrected, translated, then made shorter"></p>

## How it works for the user

**First run** (`/start`) — a 12-second demo clip in the user's interface language and a short pitch. No
questions: the user just starts writing. The only setting is the **default language** (English unless changed).

**What happens to a message** — the bot decides, no modes to choose:
- in the default language → corrected;
- in a language the user has translated into before (e.g. Polish) → corrected in that language;
- anything else, including the user's native (interface) language → translated into the default language.

**Every result** is sent as a reply to the original, containing *only* the resulting text (so copy/forward
gives exactly what you want to send), with buttons:

| | |
|---|---|
| 📋 Copy | Always shown: one-tap copy (Telegram `copy_text`) up to 256 chars, longer results are resent as a copyable code block |
| 💡 What changed | Expands up to 5 fixes: ~~original~~ → **fix** — *why* (in the user's interface language). Not shown for translations |
| 🎨 Style · 🪶 | Opens the style picker: 🎩 Formal / 😎 Casual as a one-off restyle (active one marked ✓, tap again to revert to Natural), and ✂️ Shorter |
| ✂️ Shorter | Shortens the current result, one step per tap: one sentence less while there are several, then fewer words (up to half) of the last one, after which the button disappears |
| 🇵🇱 Polski · 🇺🇦 Українська | Up to two one-tap translations: the user's most used languages, then the default and native ones |
| 🌐 Language | Opens the full picker: English, German, Spanish, Italian, French, Polish or Ukrainian. Ordered per user: current, interface language, then most used |

While the model works, only the tapped button turns into *⏳ Working on it…* — nothing jumps around.

**Also:**
- **Inline mode** — type `@your_bot some text` in *any* chat and send the fixed version without switching chats. Queries are debounced server-side so only the text the user paused on hits the model.
- **Edit your message** → the bot updates its answer in place.
- Photo **captions** and forwarded messages work too.
- `/language` — default language (`/settings` opens the same picker). `/help` — how-to with the demo clip.
- Interface in 10 languages (en, uk, ru, de, es, fr, it, pl, pt, tr), following the user's Telegram app, incl. localized command menu and the "What can this bot do?" description.
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
JAR, a thin app-layer JAR and a checksum of the library layer. The server layout matches the other bots on
the host: app in `/opt/grammar`, supervised by [immortal](https://immortal.run) (`/etc/immortal/grammar.yml`),
auto-updated by `grammar-update.timer` every 5 minutes. It runs as an unprivileged `grammar` user with its own
PostgreSQL role and database.

One-time setup (needs root; copy `deploy/` to the server first):
```bash
scp -r deploy tymur@10.42.1.16:grammar-deploy
ssh -t tymur@10.42.1.16 'sudo bash grammar-deploy/install.sh'
```
`install.sh` creates the user, database and `/opt/grammar/env` (OpenAI key and DB host copied from the
reminder bot's env, random DB password), asks for the Telegram token (validated against Telegram, username
filled in automatically) and your Telegram id for `/stats`, downloads the latest release and starts the bot.
It is safe to re-run.

Change a secret later (restarts the bot):
```bash
ssh -t tymur@10.42.1.16 'sudo /opt/grammar/set-secret.sh TELEGRAM_BOT_TOKEN'
ssh -t tymur@10.42.1.16 'sudo /opt/grammar/set-secret.sh OPENAI_API_KEY'
```

`update.sh` downloads the ~130 MB library layer only when its checksum changes, keeps the previous build
(`extracted.previous` / `grammar.jar.previous`) for rollback, and restarts only this bot's JVM.
Logs: `/var/log/grammar/app.log`, `/var/log/grammar/update.log`.

### Environment variables

| Variable | Description |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Bot token from @BotFather |
| `TELEGRAM_BOT_USERNAME` | Bot username (without @) |
| `OPENAI_API_KEY` | OpenAI API key |
| `OPENAI_MODEL` | Model (default: `gpt-6-luna`) |
| `OPENAI_TEMPERATURE_SUPPORTED` | Send temperature (default `false` — gpt-6-luna rejects it; set `true` for e.g. `gpt-5.4-mini`) |
| `OPENAI_REASONING_EFFORT` | Reasoning effort for reasoning models (default `low`; blank to omit) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | PostgreSQL (defaults: `localhost` / `5432` / `grammar` / `grammar` / `grammar`) |
| `ADMIN_CHAT_IDS` | Comma-separated chat ids allowed to use `/stats`; not rate-limited |
| `DAILY_LIMIT` | AI requests per user per day (default: 150) |
| `LOG_AI_CONTENT` | Store texts of AI calls for quality analysis (default: `true`; `false` keeps only metadata) |

### Privacy and retention

Texts are stored only so the buttons under a result keep working; they, the AI-call log and usage counters
are purged after 30 days (`app.retention-days`). When a user blocks the bot, their data is deleted immediately.

## Demo clip

`src/main/resources/onboarding/demo_<lang>.mp4` (one per interface language) and `docs/demo.gif` are rendered by
`tools/demo/generate_demo.py` (Pillow + ffmpeg) — regenerate them if the result keyboard or its labels change.
