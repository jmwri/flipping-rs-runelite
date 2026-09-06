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
4. Log in. The **Account** tab in the FlippingRS sidebar will have picked
   your default journal for this character on its own; change it there if
   this character should file somewhere else.

Each character remembers its own journal, so an alt can have its own without
you changing a setting every time you log in. That matters for more than
tidiness: buy limits are tracked per journal, so mixing two characters into
one gives you wrong limit timers as well as wrong totals.

The choice is remembered against the account rather than against whichever
character happens to be logged in, which is what lets trades an alt could not
send — a dropped connection, a key not entered yet — go out while you are
playing something else, instead of waiting for that character to log in again.

A plugin key can only do what the plugin needs: record trades, edit a
watchlist, close or delete a position, and read back the same few rows the
sidebar shows. What it cannot do is anything larger than that picture — no
paging, no filtering, no export of your journal, no changes to your account,
and no market data beyond prices for the items already in front of you. Anyone
who took the key would get your sidebar and no more, which is a much smaller
problem than a full key would be. Plugin keys are available on every plan.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| API key | — | The key from flippingrs.com |
| Record trades | on | Switch off to stop recording and stop talking to flippingrs.com |
| Send every | 30 seconds | How long to wait between sends. Trades are grouped up; nothing is lost while it waits |
| Right-click entries | on | Adds "View item" and "Add to watchlist" to items in the Grand Exchange |
| Prices when you examine | on | Adds the site's prices to the end of an item's examine line |
| Prices in the exchange | on | Shows the site's prices on the offer screen and on each of your open offers |
| Trades that couldn't be recorded | on | Tells you when flippingrs.com would not record a trade |
| An offer finishing | off | Tells you when one of your offers finishes |
| Server URL | — | Only for developers running their own copy of the site; ignored otherwise |

## The sidebar

Six tabs, named and ordered the way flippingrs.com names and orders its own
screens, so moving between the site and the sidebar is not two vocabularies
for one journal. It opens on **Journal**.

- **Watchlists** shows one of your flippingrs.com watchlists; the dropdown at
  the top picks which. Each item shows the exact price you can buy and sell at
  right now, the profit per item after tax, the return on what you'd pay, the
  buy limit and the profit across one limit, and the day's volume, refreshed
  every 30 seconds. If you have an offer on the item, that is shown too and
  updates as it fills. **Open** goes to the item's page on the site, **Remove**
  takes it off the watchlist, and **Find flips** opens the site's flip finder.
- **Journal** is your most recent trades, as your journal has them — the site's
  Journal page, which is the ledger rather than the analysis.
- **Positions** is everything you are currently holding: what you paid, the
  price a sale lists at and what an instant sale would get, your profit or loss
  so far, the price you need to sell at to break even, and a warning when
  something has sat for much longer than it usually takes to flip. Each
  position has **Close**, to record a sale at a price you enter, and
  **Delete**, for a lot that was never a flip, such as supplies you bought to
  use, so later sales of that item are not counted against it. Delete asks
  first, and your recorded trades are kept either way.
- **Analytics** is your last seven days: profit, number of flips, win rate and
  gp per hour.
- **Account** is whether the plugin is connected, which plan you are on, and
  which journal this character uses.
- **Activity** is the one tab with no page on the site, because it is about
  this computer rather than your journal: how many trades the plugin has
  recorded this session, how many are waiting to be sent, when it last sent,
  and the trades still waiting to go out. Anything about recording, such as a
  trade the site could not accept, is reported here. If any were set aside,
  this is also where you put them back in the queue and try again, which is
  worth doing after fixing whatever the site was objecting to.

Positions and Analytics are two tabs from one read: the server answers with the
week and the open lots together, so keeping them apart costs no extra request.

## In the Grand Exchange

Right-click an item anywhere in the Grand Exchange and you get **View item**,
which opens it on flippingrs.com in your browser, and **Add to watchlist**,
which puts it on the watchlist in the sidebar. Anywhere means every screen the
exchange has: your offer boxes, the setup screen, the page you get by clicking
an offer you have already placed, your history, the collection box, the
inventory beside it all, another player's offers in a view-only exchange, and
the price checker. Neither entry touches the game; they only open your browser
or update your list on the site. Both can be turned off in the settings.

When you set up a buy or sell offer, the site's exact buy and sell prices, the
margin and how old the prices are are added to the offer screen itself, under
the game's own lines — not drawn on top of it. How much of the buy limit you
have left appears too, if your plan tracks buy limits; an item that has never
traded shows no age, rather than an age of nothing.

Nothing the game shows is replaced. The item's description, the guide price and
the tax are all left exactly as they are, so if the plugin is offline or the
item has no price you lose nothing you had before.

The buy limit is counted from the trades your journal has, which is not
necessarily every trade you have made: an item bought before you installed the
plugin, or on a client that was not reporting, does not count against the
window. The error only ever goes one way — it can show more room than you
really have, never less — but it is worth knowing before you trust it.

Prices are drawn on the items themselves on all of those same screens.
On one of your own offers the plugin knows what you asked for as well as what
the item is, so it shows the price for the side you are on and how far your
offer is from it — green when your offer is priced to fill sooner, red when it
is priced to sit. The offer screen is where a flipper actually spends their
time, and what an offer box cannot tell you on its own is whether the number
you asked for is still the right one.

Everywhere else there is nothing of yours to compare against, so it shows the
two ends of the spread, and the margin alone where the box is too narrow for
both.

All of this works for any item, not only the ones on your watchlist. Nothing
is drawn for an item the site has no price for, which is the honest rendering
of not knowing. It can all be turned off.

## Examine

Examining an item puts the site's buy and sell prices and the margin on the end
of the examine line — in the inventory, the bank, or on the ground, none of
which the Grand Exchange ever sees. It is one line, not two: the prices go on
the end of the game's own text rather than following it.

An item nobody has asked the site about yet says nothing the first time and is
answered the next. A price arriving in the chat box seconds after the line it
belongs to has scrolled away is a line about nothing in particular, so it is
not sent.

## Catching up on trades it missed

The plugin can only watch while RuneLite is open with it switched on. Three
things fill the gap:

- An offer that was already part-way done when the plugin first sees it is
  sent as a recovered trade.
- Your open offers are sent when you log in, when you open the exchange, and
  after your trades go out, so the site can spot anything it missed.
- Your Grand Exchange history is sent when you open it, so trades that
  completed while the plugin was off can be added. Opening it again with the
  same screen on it sends nothing; the screen changes when an offer completes
  and is collected, which is when there is something to add.

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
- how far your computer's clock is from UTC, so the site's daily totals fall
  on your calendar day rather than somebody else's
- your API key, so the site knows the trades are yours

It also reads back what the sidebar shows: your journals, your plan, your
recent trades, your open positions and weekly summary, your watchlists, and
prices for the items on them. While the exchange is open it asks for prices
for the items it is showing, whichever of its screens is up, so those can be
priced without being on a list first. Which items you are looking at is the
only thing that tells the site, and it asks for at most forty at a time.

Like any website, flippingrs.com can see your IP address. Your character name
is never sent, and neither is anything about other players, your inventory,
your bank, where you are, or your chat. Opening an item page is an ordinary
visit in your browser.

Nothing is sent until you enter an API key, and nothing is sent while
**Record trades** is off. Trades made while it is off are not recorded as
they happen; anything already waiting is sent when you switch it back on.
Switching it off is not a way to keep a trade out of your journal for good:
once it is back on, the catch-up described above can still add an offer that
completed while it was off, saved without a time, from your open offers or
your Grand Exchange history.

Trades on Deadman, Leagues, beta, no-save, tournament, speedrunning, PvP Arena
and Fresh Start worlds are not recorded, since their prices and items have
nothing to do with the main game.

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
  It is not a grave: the commonest reason for a refusal is a wrong key or the
  wrong journal, so the sidebar offers to put those trades back in the queue,
  and does it for you when you change either of those settings.
  The one exception is a batch the site takes in and then refuses part of:
  its reply says how many rows it would not record, not which, so there is
  nothing to put in a file. Those are not sent again. The sidebar says how
  many and the client log says what the site objected to.

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
  the same item, price and size. Three things separate them. The progress going
  backwards, when the new offer has filled less than the old one had; the old
  offer having finished, since one that is bought, sold or cancelled cannot be
  running again; and the side, since a buy and a sell are not one offer however
  alike the rest of it looks. The second is the one that matters when
  a collect goes unseen and the new offer carries on past where the old one
  stopped — cancelling a part-filled buy and placing the same buy again, which
  is ordinary flipping. The third catches what progress cannot: a buy placed
  and cancelled untouched, then a sell of the same item at the same price and
  size, both sitting at zero. Two identical *finished* offers cannot be told
  apart, and are not.
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
  client does on login and hop are ignored; a fill is a fill in any state. The
  burst window above is drawn the same way: the exchange replays its slots when
  the client arrives in the world, and a map load is not an arrival, so a fill
  that lands just after one keeps the time the plugin watched it happen.

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
`shadowJar`. The shadow jar is tens of megabytes and contains its own copy of
the RuneLite client; loading that under a child classloader gives you two of
every RuneLite class and it fails with a `LinkageError`. The thin jar is a
hundred-odd kilobytes of just this plugin, which is what `PluginClassLoader`
expects.

The plugin's own sources compile against nothing but `runelite-client` and
Lombok, which is exactly what the Plugin Hub's `standard` build provides, so it
builds there without the dependency-verification step. The three test-only
dependencies — JUnit, Mockito and MockWebServer — are not part of that:
`standard` mode replaces this build file and compiles `src/main` alone, so
they are never resolved and never distributed.

Lombok is pinned at 1.18.30 to match the RuneLite plugin template, and is used
only for `@Slf4j`. If you ever need to build on a JDK past 21, the fix is to
bump it — 1.18.32 covers JDK 22, 1.18.36 covers 23, 1.18.38 covers 24, 1.18.40
covers 25 — or to drop the dependency and declare the nine loggers by hand
against slf4j-api, which `runelite-client` already provides.

The tests worth reading first are `OfferTrackerTest` — the login burst, slot
reuse, cancellation, and the int overflow, all without a game running — and
`TransactionQueueTest`, which pins the disk queue's two promises.
`FlippingRsPluginBehaviourTest` covers the orchestration: what is held, what
is set aside, and which journal the panel says it is filing under.

## How the code is laid out

The plugin class is the lifecycle, the client's events, and the capture of
fills. Everything else is a collaborator it builds in `wire()`:

- `OfferTracker` turns slot updates into fills. `TransactionQueue` keeps them
  on disk. `TransactionSender` gets them to the server and decides what a
  refusal means.
- `PanelReads` keeps the sidebar current without spending more of the rate
  limit than that is worth. `Watchlists` owns the quote caches, `CatchUp`
  reports the open slots and the history screen, `PositionActions` closes and
  deletes positions.
- `GeItems` answers "where is the exchange showing an item" for every one of
  its screens, once, so the right-click entries and the prices drawn on items
  cannot drift apart. `GeItemInfoOverlay` draws on what it finds.
- `GeSetupText` is the one thing that writes into a game interface rather than
  over it: the offer setup screen is a single fixed layout with a build script
  to hang off, it is where the number actually gets typed, and it has room
  under the game's own lines. Everywhere else the plugin paints on top, because
  a caption in the wrong place after a game update is only ugly, whereas a
  widget in the wrong place can cover something the player needed.
- `ExaminePrices` puts the same numbers on the end of an examine line. The
  message says nothing about which item it is for, so the item comes from the
  click that asked, and one click answers one message.
- `FlippingRsPanel` is the tab strip and the shared vocabulary; each tab is a
  `SidebarTab` that owns its own widgets and draws only while it is the one on
  screen. The panel asks the plugin for things through `PanelActions`, which is
  an interface rather than a dozen setters so that adding a button does not
  compile until somebody says what pressing it does.

The wire shapes are one class each — `Quote`, `Position`, `PanelData` and the
rest — and mirror the server's replies rather than the sidebar's needs, so a
field with no reader is the contract written down rather than dead code.

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
  open positions, marked to market. This and the one above are re-read after a
  send and when the sidebar is opened, at most once every fifteen seconds. A
  send only re-reads them while the sidebar is open: they are a picture of a
  panel, so there is nothing to read them for while nobody can see it.
  Connecting, picking a journal and logging in on another character read them
  whether it is open or not, because each of those changes what they would
  say.
- `GET /api/plugin/watchlists?watchlistId=`: every watchlist, and the quotes
  for the items of one of them. Re-read every thirty seconds for the quotes,
  while the sidebar or the exchange is open.

`GET /api/plugin/quote?itemId=` prices particular items, whether or not they
are on a watchlist: what the watchlist read gives for a curated list, this
gives for whatever the exchange happens to be showing. It is asked for at most
once every thirty seconds and only while the exchange is open. A server that
answers 404 is taken at its word and not asked again for the rest of the
session, so an older flippingrs.com loses the extra prices and nothing else.

A quote carries `instantBuy` and `instantSell` — the two ends of the spread, and
what the sidebar calls sell-at and buy-at — with `netMargin`, `roi`, `buyLimit`,
`profitPerLimit`, `volume24h` and `dataAgeSeconds`. Two optional fields,
`limitRemaining` and `limitResetsInSeconds`, are drawn on the offer screen when
they are sent and left off entirely when they are not: a server that says
nothing about the limit is not the same as one saying there is none left, and
drawing "0" for the first would tell somebody to stop buying an item they can
buy.

`POST /api/plugin/watchlists` and `PATCH /api/plugin/watchlists/{id}` create a
watchlist and replace its items. The plugin never deletes one.

`POST /api/plugin/positions/{id}/close` records a sale against an open
position and `DELETE /api/plugin/positions/{id}` removes a lot that was never a
flip. They are the two actions the site's own Positions page has, and the only
writes the plugin makes that are not a report of something it watched happen.
The delete is the one destructive call it can make, and the sidebar asks first;
the fills stay in the ledger either way, only the lot goes.

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
      "occurredAt": "2026-08-31T16:10:12.482Z",
      "source": "live"
    }
  ]
}
```

`id` is the idempotency key. `offerRef` identifies the one exchange offer a fill
belongs to and is repeated across every fill of it, so a thousand partial fills
are recognised as one purchase rather than a thousand. `grossValue` is the exact
gp that moved and is the only field used for money; `offerPrice` is what was
asked for. `source` is `live` for a fill the plugin watched happen and `adopted`
for one it found already done, which is sent with no `occurredAt` at all rather
than a time nobody observed.

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
