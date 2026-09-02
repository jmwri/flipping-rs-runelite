# FlippingRS for RuneLite

Records your Grand Exchange trades to your [flippingrs.com](https://flippingrs.com)
journal as they happen, so your journal shows what you actually traded
instead of what you remembered to type in.

## What it does

Every time one of your Grand Exchange offers buys or sells something, the
plugin sends that trade to flippingrs.com. The site pairs your sales with
your purchases, works out the profit after tax, and keeps track of your buy
limits. There is nothing to click: buying and selling is the whole job.

The plugin never does the maths itself. Profit, tax and which sale belongs to
which purchase are all worked out on the site, from the trades the plugin
sends. That means the numbers can be corrected and recalculated over your
whole history if the rules change, instead of being stuck in whatever version
of the plugin you installed months ago.

## Getting started

1. Install **FlippingRS** from the RuneLite Plugin Hub.
2. On flippingrs.com, go to **Account**, then **API keys**, and create a key
   for the RuneLite plugin. Copy it; it is only shown once.
3. Paste it into the plugin's **API key** setting.
4. Open the FlippingRS sidebar, go to the **Account** tab, and pick which
   journal this character's trades go into.

Each character remembers its own journal, so an alt can have its own without
you changing a setting every time you log in. That matters for more than
tidiness: buy limits are tracked per journal, so mixing two characters into
one gives you wrong limit timers as well as wrong totals.

A plugin key can only do what the plugin needs. It cannot read or export your
journal, change your account, or use the site's market data. If it ever ends
up somewhere it shouldn't, it is a much smaller problem than a full key would
be. Plugin keys are available on every plan.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| API key | — | The key from flippingrs.com |
| Record trades | on | Switch off to stop recording and stop talking to flippingrs.com |
| Send every | 30 seconds | How long to wait between sends. Trades are grouped up; nothing is lost while it waits |
| Right-click entries | on | Adds "View item" and "Add to watchlist" to items in the Grand Exchange |
| Prices on the offer screen | on | Shows the site's exact buy and sell prices when you set up an offer for a watched item |
| Server URL | — | Only for developers running their own copy of the site; ignored otherwise |

## The sidebar

Five tabs:

- **Activity** is what the plugin itself is doing: how many trades it has
  recorded this session, how many are waiting to be sent, when it last sent,
  and the trades still waiting to go out. Anything about recording, such as
  a trade the site could not accept, is reported here.
- **Trades** is your most recent trades, as your journal has them.
- **Journal** is your last seven days, with profit, number of flips, win rate
  and gp per hour, and everything you are currently holding: what you paid,
  the price a sale lists at and what an instant sale would get, your profit or
  loss so far, the price you need to sell at to break even, and a warning when
  something has sat for much longer than it usually takes to flip. Each
  position has **Close**, to record a sale at a price you enter, and
  **Delete**, for a lot that was never a flip, such as supplies you bought to
  use, so later sales of that item are not counted against it. Delete asks
  first, and your recorded trades are kept either way.
- **Watchlists** shows one of your flippingrs.com watchlists. Each item shows
  the exact price you can buy and sell at right now, the profit per item after tax,
  the return on what you'd pay, the buy limit and the profit across one limit,
  and the day's volume, refreshed every 30 seconds. If you have an offer on the
  item, that is shown too and updates as it fills. **Open** goes to the item's
  page on the site and **Find flips** opens the site's flip finder.
- **Account** is whether the plugin is connected, which plan you are on, and
  which journal this character uses.

## In the Grand Exchange

Right-click an item anywhere in the Grand Exchange, whether one of your offer
slots, an item beside them, the offer setup screen, or a row in your history.
**View item** opens it on flippingrs.com in your browser. **Add to watchlist**
puts it on the watchlist in the sidebar. Neither one touches the game; they
only open your browser or update your list on the site. Both can be turned off
in the settings.

When you set up a buy or sell offer for an item that is on your watchlist, the
site's exact buy and sell prices and the margin appear in the corner of the
offer screen, so the number to type is right there. That can be turned off
too.

## Catching up on trades it missed

The plugin can only watch while RuneLite is open with it switched on. Three
things fill the gap:

- An offer that was already part-way done when the plugin first sees it is
  sent as a recovered trade.
- Your open offers are sent when you log in, when you open the exchange, and
  after your trades go out, so the site can spot anything it missed.
- Your Grand Exchange history is sent when you open it, so trades that
  completed while the plugin was off can be added.

In all three cases the site checks what it already has, so nothing is added
twice, and recovered trades are saved without a time rather than pretending
they happened just now.

## What is sent, and to whom

flippingrs.com is a third-party service, not run or checked by the RuneLite
team. The plugin sends it:

- each trade: the item, how many, the price and the gp that changed hands,
  whether it was a buy or a sell, the slot and world, and when
- your open offers and what your Grand Exchange history shows, so missed
  trades can be caught up
- items you add to or remove from a watchlist
- a sale you record against a position, or a position you delete
- which journal you picked for this character
- your API key, so the site knows the trades are yours

It also reads back what the sidebar shows: your journals, your plan, your
recent trades, your open positions and weekly summary, your watchlists, and
prices for the items on them.

Like any website, flippingrs.com can see your IP address. Your character name
is never sent, and neither is anything about other players, your inventory,
your bank, where you are, or your chat. Opening an item page is an ordinary
visit in your browser.

Nothing is sent until you enter an API key, and nothing is sent while
**Record trades** is off. Trades made while it is off are not recorded;
anything already waiting is sent when you switch it back on.

Trades on Deadman, Leagues, beta, tournament, speedrunning, PvP Arena and
Fresh Start worlds are not recorded, since their prices and items have nothing
to do with the main game.

The plugin always talks to `https://flippingrs.com`. The **Server URL**
setting only works when RuneLite is started in developer mode, so nothing can
redirect your key or your trades anywhere else.

## Three promises

- **It never records a trade twice.** Every trade carries its own id, and the
  site ignores one it has already seen, so the plugin can safely try again
  whenever a send fails.
- **It never makes a trade up.** Trades it did not watch happen are sent
  marked as recovered, with no time, and the site decides whether they are
  new. Nothing is ever dated "now" when it did not happen now.
- **It does not lose trades to a bad connection.** Every trade is saved to
  disk before it is sent and stays there until the site confirms it. Closing
  RuneLite, dropping your connection, a wrong key, a lapsed plan or a deleted
  journal all just hold your trades until things are fixed. The one thing the
  site will not accept, a trade it says is malformed, is kept in a file in
  your RuneLite folder rather than deleted, and the sidebar tells you where.

---

Everything below this line is for people working on the plugin.

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
