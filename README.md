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
| Record trades | on | Off means nothing is captured **and** nothing is sent |
| Send every | 30s | How long fills are batched before sending |
| Right-click entries | on | "View item" and "Add to watchlist" on Grand Exchange items, including the setup page and history |
| Server URL | — | Developer mode only; ignored in a normal install |

## In the Grand Exchange

Right-clicking an item anywhere the exchange shows one adds two entries: the
offer slots, the inventory beside them, the offer setup page, and the rows of
the history view. **View item** opens the item's page on the site in your
browser. **Add to watchlist** puts it on the watchlist shown in the side panel.

The side panel has five tabs. **Activity** is the plugin's own doing: what it
has captured this session, the fills still buffered and waiting to send, and
when it last sent. **Trades** is the journal's recent rows. **Journal** is the
journal's verdict: the last seven days' realised profit, win rate and gp per
hour, and every open position marked to market, with its cost, current price,
unrealised profit, break-even price and whether it has gone stale.
**Watchlists** is one of your watchlists on the site. **Account** is the
connection: whether the key works, the plan it is on, and which journal this
RuneScape account files under. Each tab carries its own messages, so a
refused batch is reported on Activity and a plan limit on Watchlists, and
neither overwrites the other.

The watchlist is one of your watchlists on flippingrs.com, and the panel picks
which. The plugin keeps no copy: an add or a remove goes to the server first
and the panel is redrawn from what comes back, and an account with no
watchlist gets one called "Plan" on its first add. Each card shows the item's
sprite and the site's own quote: the price to buy at and sell at, the margin
per item after tax, the return on the buy price, the buy limit, the profit
across one limit, and the day's volume. Those numbers come with the panel
read, refreshed once a minute, and are exactly what the item's page shows;
the plugin formats them and computes nothing, for the same reason it does not
compute profit. An item the site has no quote for falls back to the client's
own price, buy limit and alch value. Any offer you have on the item right now
is shown too, and updates as it fills. **Open** goes to the item's page and
**Find flips** to the site's finder.

The same goes for the panel's recent trades. They are read back from the
journal after every send, rather than remembered from what was sent, so what
the panel shows is what was actually recorded. The two only differ when
something has gone wrong, which is exactly when it matters.

Both entries are client-side only. Jagex's third-party client guidelines forbid
menu entries that send an action to the game server; these open a browser or
edit a list on the site and never touch the game. They can be turned off with
**Right-click entries**.

## What is sent, and to whom

This plugin sends data to flippingrs.com, a third-party server that is not
controlled or verified by the RuneLite developers. Specifically, for every
Grand Exchange fill:

- the item, quantity, price, gp value, and whether it was a buy or a sell
- the exchange slot, the world, and the time it happened
- an id and an offer reference, both generated locally, so the server can
  recognise repeats and group fills into one offer
- the FlippingRS journal you picked for that RuneScape account
- your API key, as an authentication header

And, outside of fills: the state of your open Grand Exchange offers and the
rows of the exchange's history screen, so the server can catch up on what
happened while the plugin was not running; the item id you add to or remove
from a watchlist; and reads of the side panel's tabs, which return your journals, your plan, the
journal's recent rows, open positions and weekly summary, your watchlists
and the site's quotes for the watched items. Quotes are re-read once a
minute while you have a watchlist. Nothing about the plan is kept on this machine beyond
which watchlist is picked.

As with any HTTP request, your IP address is visible to the server.

Your RuneScape display name is **not** sent, and neither is anything about other
players, your inventory, your bank, your location, or your chat. The plugin
reads nothing but Grand Exchange offer events and item names. Opening an item
page from the right-click menu or the watchlist is an ordinary browser visit
to flippingrs.com, and sends nothing from the plugin.

Nothing at all is sent until you enter an API key, and nothing is sent while
**Record trades** is off — that setting stops the plugin contacting the server
entirely, not merely capturing. Fills captured before you turned it off stay
queued on disk and go out when you turn it back on; fills that happen while it
is off are discarded.

Fills on worlds with their own economy — Deadman, Leagues and other seasonal
worlds, beta, tournament, speedrunning, PvP Arena and Fresh Start worlds — are
not recorded at all. Their prices, limits and, at the end of a season, their
items have nothing to do with the journal.

The destination is fixed at `https://flippingrs.com` in a normal install.
There is a **Server URL** setting, for running the plugin against a local
server, but it is only read when the client was started with
`--developer-mode` and is ignored outright otherwise, so it cannot redirect an
ordinary install's key or trades anywhere.

## The three things it will not do

These are the properties everything else is arranged around, because breaking
any one of them means wrong money in your journal.

**It never records a trade twice.** Every fill gets an id before it is first
sent, and the server drops an id it has already seen. That makes retrying free,
which in turn makes it safe to queue and resend rather than hope.

**It never invents a trade.** If you install the plugin mid-flip, or log in on a
new machine, a slot may already be part filled with no baseline to subtract
from. That progress becomes the new baseline, and goes out once as a fill
marked *recovered* with no time on it: it is real, but nobody watched it
happen, and it may already be in the journal from another machine, so the
server decides whether it is new. It is never dated "now", because a sale
stamped after the purchase it belongs to turns a real flip into an unmatched
sale. The same goes for what the client replays in the first two ticks after
login: an offer that filled while you were logged out goes out untimed too. The same rule covers the two other ways the plugin catches the server
up on what happened while it was not running: it sends the state of your open
offers on login, when you open the exchange and after each batch, and it sends
the exchange's history screen when you open it. In both cases it reports what
the game shows and the server matches that against what it already has.

**It does not lose trades to a flaky network.** Fills are written to disk before
they are sent and stay there until the server confirms them, so closing the
client or dropping the connection costs nothing. Closing the client also gets
one last send, so the evening's final trades do not wait for the next login.

The same rule covers a bad key. A revoked, mistyped or wrong-scoped key, a
lapsed plan, or a journal that no longer exists all hold the queue rather than
emptying it, because none of those is a fault in the trades themselves. Fix the
key or pick a journal and everything that piled up goes out. The only thing
that is dropped from the queue is a batch the server says is malformed, and
even that is not deleted: it is appended to `dropped-<account>.json` beside the
queue in `~/.runelite/flippingrs/`, the panel says so, and you can enter it by
hand.

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
  32-bit int. The exchange caps an offer at max cash, which is also the largest
  int, so in practice it cannot wrap, but a figure that becomes profit is not
  left to "in practice". The difference between two looks is taken modulo
  2^32, which is exact across one wrap; a fill that price times quantity alone
  puts beyond an int is not trusted to the total at all; and what is left is
  checked against rules the exchange guarantees — a buy never fills above your
  offer, a sale never fills below your ask, no offer moves more than max cash.
  Anything that fails is replaced with price times quantity and flagged as
  approximate. A figure known to be approximate is worth much more than one
  that is exactly wrong.
- **Region loads.** The client's state leaves `LOGGED_IN` briefly whenever a
  new area loads, and an offer can fill during that. Only the slot clears the
  client does on login and hop are ignored; a fill is a fill in any state.

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
`FlippingRsPluginBehaviourTest` covers the orchestration: what is held, what
is set aside, and which journal the panel says it is filing under.

## The API it talks to

Everything goes through `/api/plugin`, authenticated with `X-Api-Key`, and
nothing else. That prefix is the boundary between what a plugin key may do and
what the Elite plan's API sells: a plugin key is available on every plan, so
its surface is kept to things that are only ever a picture of the sidebar.

One read per tab, each capped and unfilterable by design:

- `GET /api/plugin/account`: the owner's plan and their journals. Doubles as
  the connection test.
- `GET /api/plugin/trades?accountId=`: the last eight recorded fills.
- `GET /api/plugin/journal?accountId=&tzOffset=`: the week's summary and the
  open positions, marked to market.
- `GET /api/plugin/watchlists?watchlistId=`: every watchlist, and the quotes
  for the items of one of them. Re-read once a minute for the quotes.

`POST /api/plugin/watchlists` and `PATCH /api/plugin/watchlists/{id}` create a
watchlist and replace its items. The plugin never deletes one.

`POST /api/plugin/offers` takes the open slots and `POST /api/plugin/history`
the history screen's rows, for the server to reconcile against the fills it
has; each answers with how much it already had and how much it took on.

`POST /api/plugin/transactions` takes a batch of fills:

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
