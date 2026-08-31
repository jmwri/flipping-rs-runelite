# flipping-rs-runelite

A RuneLite plugin that records your Grand Exchange trades to your
[flippingrs.com](https://flippingrs.com) journal as they happen, so the journal
is a record of what you actually traded rather than something you have to
remember to type in.

## What it does

Every time a Grand Exchange slot fills, the plugin works out what changed since
it last looked and sends that one fill to FlippingRS. The server matches fills
into flips, computes the profit after the 2% sale tax, and advances the 4-hour
buy-limit window. Buying and selling is the whole interface; there is nothing to
click.

**The plugin decides nothing.** It never pairs a sale with a purchase, works out
what a flip earned, or applies the tax. That all happens server-side, from the
fills sent here, so the maths can be fixed and replayed over your history rather
than being frozen inside whichever plugin version you installed months ago.

## Setup

1. Install **FlippingRS** from the RuneLite Plugin Hub.
2. On flippingrs.com, go to **Account → API keys**, create a key with the
   **RuneLite plugin** scope, and copy it. It is shown once.
3. Paste it into the plugin's **API key** setting.
4. Open the FlippingRS side panel and pick which journal this RuneScape account
   files under.

The journal choice is remembered **per RuneScape account**, not globally, so an
alt keeps its own journal without you switching a setting before you log in.
That matters beyond tidiness: buy limits are tracked per game account, so mixing
two accounts produces wrong limit timers as well as wrong totals.

A plugin-scoped key reaches trade ingestion and the account list, and nothing
else. It cannot read or edit your journal, and it cannot touch the market API,
so a key pasted into the wrong place is a much smaller problem than a full one.
Plugin keys are available on every plan; the full public API stays Elite.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| API key | — | Plugin-scoped key from flippingrs.com |
| Record trades | on | Turning it off discards trades rather than queueing them |
| Send every | 30s | How long fills are batched before sending |
| Base URL | `https://flippingrs.com` | Only change it to point at your own instance |

## The three things it will not do

These are the properties everything else is arranged around, because breaking
any one of them means wrong money in your journal.

**It never records a trade twice.** Every fill gets an id before it is first
sent, and the server drops an id it has already seen. That makes retrying free,
which in turn makes it safe to queue and resend rather than hope.

**It never invents a trade.** If you install the plugin mid-flip, or log in on a
new machine, a slot may already be part filled with no baseline to subtract
from. That progress is adopted silently as the new baseline rather than
backdated to now. You may then see a sale that does not match a purchase, and
the panel says so. A missing trade is visible and fixable; an invented one is
neither, and it quietly poisons every average built on top of it.

**It does not lose trades to a flaky network.** Fills are written to disk before
they are sent and stay there until the server confirms them, so closing the
client or dropping the connection costs nothing.

## How it reads the exchange

The client does not announce trades. It reports what a slot looks like, over and
over, and a trade is the difference between two consecutive looks. Most of the
work is in the cases where that is not straightforward:

- **The login burst.** The client re-fires an event for every occupied slot on
  login, carrying the quantity already sold. The baseline is persisted per
  RuneScape account, so the difference comes out as zero instead of re-reporting
  everything on the exchange every time you log in.
- **Slot reuse.** Collecting a finished offer and placing an identical one gives
  the same item, price and size. Only the progress going backwards distinguishes
  them.
- **A running total that is an `int`.** `GrandExchangeOffer#getSpent` is a
  32-bit int, and a thousand items at three million each overflows it. A wrapped
  total is caught by a rule the exchange guarantees — a buy never fills above
  your offer, a sale never fills below your ask — and replaced with price times
  quantity, flagged as approximate. A figure known to be approximate is worth
  much more than one that is exactly wrong.

Exact gp comes from the `spent` delta rather than price times quantity wherever
it can, because a buy fills at or under your offer and a part-filled offer mixes
prices. The difference is your money, and it should show up as profit.

## Building

Needs **JDK 17 or 21** on the PATH. That window is the intersection of two
constraints, and stepping outside it fails in ways that do not name themselves:

- The wrapper pins Gradle 9.6, and Gradle 9 needs a JVM between 17 and 26 to
  run at all. JDK 11 is out.
- Lombok 1.18.30 (the version the RuneLite plugin template pins) supports up to
  JDK 21. On anything newer it dies in javac internals with
  `com.sun.tools.javac.code.TypeTag :: UNKNOWN`, which does not mention Lombok.

The output is Java 11 bytecode either way (`options.release.set(11)`), which is
what the client loads and what the Plugin Hub builds against.

```bash
./gradlew test         # unit tests
./gradlew run          # your normal client, with this plugin loaded
./gradlew jar          # the thin plugin jar, build/libs/flippingrs-<v>.jar
```

**`./gradlew run` is how you run this against your real setup.** It starts the
same client version the launcher installed, against the same `~/.runelite`
directory — your profile, your settings, and your Plugin Hub plugins all load
alongside it. There is no install step and nothing to copy.

You cannot get a locally built plugin into a client started by `RuneLite.exe`.
The side-load directory is only read in developer mode, and developer mode is

    options.has("developer-mode") && RuneLiteProperties.getLauncherVersion() == null

The launcher always sets `runelite.launcher.version`, so that is always false —
passing `--developer-mode` to `RuneLite.exe` does nothing, silently. Getting it
into a launcher-started client means the Plugin Hub.

If you do side-load into a client you started yourself, use `./gradlew jar`, not
`shadowJar`. The shadow jar is 31 MB and contains its own copy of the RuneLite
client; loading that under a child classloader gives you two of every RuneLite
class and it fails with a `LinkageError`. The thin jar is 31 KB of just this
plugin, which is what `PluginClassLoader` expects.

Every dependency is a transitive of `runelite-client`, so this builds on the
Plugin Hub in `standard` mode without the dependency-verification step.

Lombok is pinned at 1.18.30 to match the RuneLite plugin template, and is used
only for `@Slf4j`. If you ever need to build on a JDK past 21, the fix is to
bump it — 1.18.32 covers JDK 22, 1.18.36 covers 23, 1.18.38 covers 24, 1.18.40
covers 25 — or to drop the dependency and declare the three loggers by hand
against slf4j-api, which `runelite-client` already provides.

The tests worth reading first are `OfferTrackerTest` — the login burst, slot
reuse, cancellation, and the int overflow, all without a game running — and
`TransactionQueueTest`, which pins the disk queue's two promises.

## The API it talks to

Two endpoints, both authenticated with `X-Api-Key`.

`GET /api/journal/accounts` lists the journals a key's owner has. It doubles as
the connection test.

`POST /api/journal/transactions` takes a batch of fills:

```json
{
  "accountId": "the FlippingRS game account",
  "transactions": [
    {
      "id": "8f1c...",
      "offerRef": "3b90...",
      "itemId": 4151,
      "itemName": "Abyssal whip",
      "side": "buy",
      "quantity": 25,
      "grossValue": 30864175,
      "offerPrice": 1250000,
      "offerTotal": 100,
      "completed": false,
      "cancelled": false,
      "estimated": false,
      "slot": 3,
      "world": 302,
      "occurredAt": "2026-08-31T16:10:12.482Z"
    }
  ]
}
```

`id` is the idempotency key. `offerRef` identifies the one exchange offer a fill
belongs to and is repeated across every fill of it, so a thousand partial fills
are recognised as one purchase rather than a thousand. `grossValue` is the exact
gp that moved and is the only field used for money; `offerPrice` is what was
asked for.

The response says what happened, which is what the panel shows:

```json
{
  "accepted": 2, "duplicate": 0, "rejected": 0,
  "flipsOpened": 1, "flipsClosed": 1,
  "unmatchedSellQty": 0,
  "problems": [], "flipIds": ["..."]
}
```

`unmatchedSellQty` is quantity sold with no recorded purchase behind it — stock
bought before the plugin was installed, or on another client. It is reported
rather than invented, because a lot with a made-up cost basis shows an infinite
return and poisons the analytics.

## Licence

BSD 2-Clause, matching RuneLite.
