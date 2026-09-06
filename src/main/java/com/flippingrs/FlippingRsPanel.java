package com.flippingrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The side panel, in five tabs.
 *
 * <ul>
 *   <li><b>Activity</b> is the plugin's own doing: what it has captured this
 *       session, what is still buffered waiting to send, and when it last
 *       sent. The buffered fills are listed, because a fill sitting here is
 *       the one thing the plugin holds that the journal does not yet.
 *   <li><b>Trades</b> is the journal's recent rows, read back from the
 *       server: what was actually recorded, not what the plugin remembers
 *       sending. The two differ exactly when something went wrong.
 *   <li><b>Journal</b> is the journal's verdict: the last week's realised
 *       profit and the open positions, marked to market by the server.
 *   <li><b>Watchlists</b> is one of the owner's watchlists on the site, each
 *       item with the site's buy and sell prices, margin after tax and ROI.
 *   <li><b>Account</b> is the connection: whether the key works, what plan it
 *       is on, and which journal this RuneScape account files under.
 * </ul>
 *
 * <p>Every number about money on any of these tabs is the server's. The
 * plugin formats; it does not compute.
 *
 * <p>Messages go to the tab they are about, so each tab answers its own
 * question without the others' news overwriting it.
 *
 * <p>Every method here must be called on the Swing thread. The plugin marshals.
 */
public class FlippingRsPanel extends PluginPanel
{
	static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	static final DateTimeFormatter SHORT_TIME = DateTimeFormatter
		.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	static final DateTimeFormatter DAY = DateTimeFormatter
		.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

	static final int RECENT_SHOWN = 8;

	/** This panel's own border, on each side. */
	static final int PANEL_PADDING = 10;
	/** A card's padding, on each side. */
	static final int CARD_PADDING = 6;
	/** The sprite at the head of a card, and the gap between it and the title. */
	static final int ICON_WIDTH = 36;
	static final int ICON_GAP = 6;

	/**
	 * How long a notice stays up. A notice is news -- a recovered trade, an
	 * item added, a refused batch -- and news that never leaves stops being
	 * read. Anything that still matters after this is somewhere permanent:
	 * the log, the set-aside file, the tab's own problem line.
	 */
	static final int NOTICE_SECONDS = 20;

	/** What the watchlist shows for one item. Resolved by the plugin, drawn here. */
	static class WatchedItem
	{
		final int itemId;
		final String name;
		/** The item's sprite, filled in when the client has drawn it. Null in a test. */
		@Nullable
		final AsyncBufferedImage image;
		/** RuneLite's current exchange price for the item, or 0 if it has none. */
		final int price;
		/** The four-hour buy limit, or 0 if unknown. */
		final int limit;
		/** High alchemy value, or 0. */
		final int alch;
		/** The player's live offer on the item, e.g. "Buying 4/10 at 1.50M", or null. */
		@Nullable
		final String offer;
		/** The site's quote, or null if it has none or could not be reached. */
		@Nullable
		final Quote quote;

		WatchedItem(int itemId, String name, @Nullable AsyncBufferedImage image,
			int price, int limit, int alch, @Nullable String offer, @Nullable Quote quote)
		{
			this.itemId = itemId;
			this.name = name;
			this.image = image;
			this.price = price;
			this.limit = limit;
			this.alch = alch;
			this.offer = offer;
			this.quote = quote;
		}
	}

	static final Border SELECTED_TAB = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.BRAND_ORANGE),
		BorderFactory.createEmptyBorder(4, 3, 3, 3));
	static final Border UNSELECTED_TAB = BorderFactory.createEmptyBorder(4, 3, 4, 3);

	/**
	 * A tab with less padding and the narrower font, so five fit in a
	 * 205-pixel sidebar in two rows. RuneLite's own tab re-applies its wide
	 * border on every select and unselect, hence the overrides.
	 *
	 * <p>Selecting one is also what draws it. Four of the five are off screen
	 * at any moment and building their contents costs real time -- see
	 * {@link #drawWhatIsShowing}.
	 */
	private class Tab extends MaterialTab
	{
		Tab(String name, MaterialTabGroup group, JComponent content)
		{
			super(name, group, content);
			setFont(FontManager.getRunescapeSmallFont());
			setHorizontalAlignment(SwingConstants.CENTER);
			unselect();
		}

		@Override
		public boolean select()
		{
			final boolean selected = super.select();
			setBorder(SELECTED_TAB);
			showing = this;
			drawWhatIsShowing();
			return selected;
		}

		@Override
		public void unselect()
		{
			super.unselect();
			setBorder(UNSELECTED_TAB);
		}
	}

	/** The tab whose contents are on screen; see {@link SidebarTab}. */
	@Nullable
	private MaterialTab showing;

	/** The five tabs, each owning its own widgets and its own deferred redraw. */
	private final ActivityTab activity;
	private final TradesTab trades;
	private final JournalTab journal;
	private final WatchlistTab watchlist;
	private final AccountTab account;

	private final MaterialTabGroup tabs;
	private final MaterialTab activityTab;
	private final MaterialTab tradesTab;
	private final MaterialTab journalTab;
	private final MaterialTab watchlistTab;
	private final MaterialTab accountTab;

	/**
	 * Set while the plugin is not reading from the server at all: recording
	 * off, or no key. The tabs that show the server's data show this instead
	 * of stale rows. Cleared by the next data that arrives.
	 */
	@Nullable
	private String paused;

	/** What the plugin does when the sidebar is used. Never null. */
	private final PanelActions actions;

	public FlippingRsPanel(PanelActions actions)
	{
		this.actions = actions;
		activity = new ActivityTab(actions);
		trades = new TradesTab();
		journal = new JournalTab(actions);
		watchlist = new WatchlistTab(actions);
		account = new AccountTab(actions);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(PANEL_PADDING, PANEL_PADDING, PANEL_PADDING, PANEL_PADDING));

		final JPanel top = column();
		top.add(header("FlippingRS"));
		top.add(Box.createVerticalStrut(6));

		final JPanel display = new JPanel();
		tabs = new MaterialTabGroup(display);
		activityTab = new Tab("Activity", tabs, activity.body());
		tradesTab = new Tab("Trades", tabs, trades.body());
		journalTab = new Tab("Journal", tabs, journal.body());
		watchlistTab = new Tab("Watchlists", tabs, watchlist.body());
		accountTab = new Tab("Account", tabs, account.body());
		for (MaterialTab tab : new MaterialTab[]{activityTab, tradesTab, journalTab, watchlistTab, accountTab})
		{
			tabs.addTab(tab);
		}
		// Two rows of three. The group's own layout is a FlowLayout, which
		// wraps what does not fit onto a second row and then reports the
		// height of one, so anything past the first row was laid out below
		// the visible strip and never painted.
		tabs.setLayout(new GridLayout(2, 3, 2, 2));
		tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
		tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, tabs.getPreferredSize().height));
		top.add(tabs);
		top.add(Box.createVerticalStrut(8));
		display.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(display);

		add(top, BorderLayout.NORTH);

		setStatus("Not connected", ColorScheme.LIGHT_GRAY_COLOR);
		setSubscription(null);
		setCounts(0, 0);
		setLastSync(null, null);
		setActivityNotice(null, ColorScheme.LIGHT_GRAY_COLOR);
		setWatchlistNotice(null, ColorScheme.LIGHT_GRAY_COLOR);
		setJournalNotice(null, ColorScheme.LIGHT_GRAY_COLOR);
		setWatchlists(new ArrayList<>(), null);
		// Every tab is now holding data it has not drawn. Selecting the first
		// draws that one; the other four draw when they are selected.
		for (SidebarTab tab : new SidebarTab[]{activity, trades, journal, watchlist, account})
		{
			tab.refresh();
		}
		tabs.select(activityTab);
	}

	static JPanel column()
	{
		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		return body;
	}

	/**
	 * Draws the list on the tab that has just come on screen, if its data
	 * moved while it was off.
	 *
	 * <p>Each of these skips itself when its own tab is not the one showing,
	 * so calling all four is how the one that is gets drawn. The labels above
	 * each list are set as the data arrives and cost nothing; it is the lists
	 * that are dear, because each row is a card built out of several labels
	 * and, on two of the tabs, a pair of buttons. Two hundred open positions
	 * measure at around 98ms to build, and that was being paid on every
	 * journal read whichever tab the user was looking at.
	 */
	private void drawWhatIsShowing()
	{
		activity.showing(showing == activityTab);
		trades.showing(showing == tradesTab);
		journal.showing(showing == journalTab);
		watchlist.showing(showing == watchlistTab);
		account.showing(showing == accountTab);
	}

	/** RuneLite calls this when the panel becomes the sidebar's content. */
	@Override
	public void onActivate()
	{
		actions.shown();
	}

	/** And this when it stops being. */
	@Override
	public void onDeactivate()
	{
		actions.hidden();
	}

	/** A line under a tab's title saying what the tab shows and where it comes from. */
	static JPanel hint(String text)
	{
		final JPanel holder = column();
		final JLabel label = small(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		holder.add(label);
		holder.add(Box.createVerticalStrut(8));
		holder.setAlignmentX(Component.LEFT_ALIGNMENT);
		return holder;
	}

	static JLabel header(String text)
	{
		final JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	// ------------------------------------------------------------- the tabs
	//
	// One line each, on to the tab that owns the widgets. The plugin and its
	// reads talk to the panel, not to five tabs, so which tab holds what
	// stays this class's business rather than becoming everybody's.

	/** The connection status, on the Account tab. */
	void setStatus(String text, Color colour)
	{
		account.setStatus(text, colour);
	}

	/** The plan the key's owner is on, in the server's words, or null if not known. */
	void setSubscription(@Nullable String text)
	{
		account.setSubscription(text);
	}

	void setAccounts(List<GameAccount> available, @Nullable String selectedId)
	{
		account.setAccounts(available, selectedId);
	}

	@Nullable
	String selectedAccountId()
	{
		return account.selectedAccountId();
	}

	void setCounts(int recordedCount, int queuedCount)
	{
		activity.setCounts(recordedCount, queuedCount);
	}

	void setLastSync(@Nullable Instant at, @Nullable String problem)
	{
		activity.setLastSync(at, problem);
	}

	void setActivityNotice(@Nullable String text, Color colour)
	{
		activity.setActivityNotice(text, colour);
	}

	void setPending(List<GeTransaction> newestFirst)
	{
		activity.setPending(newestFirst);
	}

	/** How many fills the server refused for good and the plugin filed. */
	void setSetAside(int count)
	{
		activity.setSetAside(count);
	}

	void setActivity(List<GeTransaction> newestFirst)
	{
		resumed();
		trades.setActivity(newestFirst);
	}

	void setActivity(List<GeTransaction> newestFirst, Map<Integer, AsyncBufferedImage> images)
	{
		resumed();
		trades.setActivity(newestFirst, images);
	}

	void setActivityProblem(String why)
	{
		trades.setActivityProblem(why);
	}

	void setJournal(Analytics week, Positions open)
	{
		resumed();
		journal.setJournal(week, open);
	}

	void setJournalNotice(@Nullable String text, Color colour)
	{
		journal.setJournalNotice(text, colour);
	}

	void setJournalProblem(String why)
	{
		journal.setJournalProblem(why);
	}

	void closeAsTyped(Position p, String priceText, String quantityText)
	{
		journal.closeAsTyped(p, priceText, quantityText);
	}

	void closePosition(String positionId, long sellPrice, @Nullable Long sellQty)
	{
		journal.closePosition(positionId, sellPrice, sellQty);
	}

	void setWatchlists(List<Watchlist> available, @Nullable String selectedId)
	{
		watchlist.setWatchlists(available, selectedId);
	}

	@Nullable
	String selectedWatchlistId()
	{
		return watchlist.selectedWatchlistId();
	}

	void setWatchlistItems(List<WatchedItem> items)
	{
		resumed();
		watchlist.setWatchlistItems(items);
	}

	void updateWatchedOffer(int itemId, @Nullable String offer)
	{
		watchlist.updateWatchedOffer(itemId, offer);
	}

	void setWatchlistProblem(String why)
	{
		watchlist.setWatchlistProblem(why);
	}

	void setWatchlistNotice(@Nullable String text, Color colour)
	{
		watchlist.setWatchlistNotice(text, colour);
	}

	// ---------------------------------------------------------------- account

	/**
	 * Nothing is being read from the server, for the reason given. Trades,
	 * Journal and Watchlists show the reason instead of whatever they last
	 * held, and the plan line is cleared.
	 */
	void setPaused(String why)
	{
		paused = why;
		setWatchlists(new ArrayList<>(), null);
		setSubscription(null);
		trades.paused(why);
		journal.paused(why);
		watchlist.paused(why);
	}

	/**
	 * The plugin is reading from the server again, so the tabs that were
	 * showing the reason it was not are redrawn.
	 *
	 * <p>All three, not only the one whose data has just arrived. The reason
	 * was put on every tab at once by {@link #setPaused}, but it was taken off
	 * by whichever tab was read first, and the other two went on saying that
	 * nothing was being read from flippingrs.com while it plainly was. For a
	 * character with no journal picked that is not a moment: Trades and Journal
	 * are not read at all without one, so the Watchlists read cleared the flag
	 * and the other two kept the sentence for the rest of the session.
	 */
	private void resumed()
	{
		if (paused == null)
		{
			return;
		}
		paused = null;
		trades.paused(null);
		journal.paused(null);
		watchlist.paused(null);
	}

	// --------------------------------------------------------------- activity

	// ----------------------------------------------------------------- trades

	// ---------------------------------------------------------------- journal

	/** Field and row separators for {@link #signatureOf}, kept out of any name. */
	static final char SEP = (char) 0;
	static final char ROW = (char) 10;

	// ------------------------------------------------------------- watchlists

	// ------------------------------------------------------------ trades
	//
	// The words a trade row is made of. Static and stateless, so the
	// tab that draws them and the tests that pin them share one copy.

/** "Bought 4 for 3,800,000 (950,000 each)", with "(approx)" when the total was estimated. */
	static String whatHappened(GeTransaction tx)
	{
		final StringBuilder out = new StringBuilder("buy".equals(tx.side) ? "Bought " : "Sold ");
		out.append(tx.quantity).append(" for ").append(exact(tx.grossValue));
		if (tx.quantity > 1)
		{
			out.append(" (").append(exact(Math.round((double) tx.grossValue / tx.quantity))).append(" each)");
		}
		if (tx.estimated)
		{
			out.append(" (approx)");
		}
		return out.toString();
	}

	/** "Today 12:00:01", "Yesterday 18:32", "2 Sep 18:32", or "Recovered, time unknown". */
	static String when(GeTransaction tx, Instant now)
	{
		if (tx.occurredAt == null)
		{
			return "Recovered, time unknown";
		}
		final Instant at;
		try
		{
			at = Instant.parse(tx.occurredAt);
		}
		catch (RuntimeException e)
		{
			return "Time unknown";
		}
		final ZoneId zone = ZoneId.systemDefault();
		final LocalDate day = at.atZone(zone).toLocalDate();
		final LocalDate today = now.atZone(zone).toLocalDate();
		if (day.equals(today))
		{
			return "Today " + TIME.format(at);
		}
		if (day.equals(today.minusDays(1)))
		{
			return "Yesterday " + SHORT_TIME.format(at);
		}
		return DAY.format(at);
	}

	/** One fill as a line: its own time, or "recovered" when it has none, then side, quantity, item and gp. */
	static String line(GeTransaction tx)
	{
		return at(tx) + "  "
			+ ("buy".equals(tx.side) ? "Bought " : "Sold ")
			+ tx.quantity + " x " + nameOf(tx)
			+ " for " + exact(tx.grossValue) + (tx.estimated ? " (approx)" : "");
	}

	/**
	 * When a fill happened, for the buffer list: the time it carries,
	 * "recovered" when it never had one, and "unknown" when it has one that
	 * cannot be read.
	 *
	 * <p>That last case used to read as the current time. A fill whose stamp
	 * is unreadable -- a row restored from a damaged queue file -- is not a
	 * fill that happened just now, and the whole of this plugin's dealings
	 * with time rest on never claiming one it does not have.
	 */
	static String at(GeTransaction tx)
	{
		if (tx.occurredAt == null)
		{
			return "recovered";
		}
		try
		{
			return TIME.format(Instant.parse(tx.occurredAt));
		}
		catch (RuntimeException e)
		{
			return "unknown";
		}
	}

	/**
	 * What to call the item. The name is resolved when the fill is captured
	 * and can be missing -- an item the client would not name, or a row
	 * restored from a queue file an older version wrote -- and the id is a
	 * good deal more use to anyone reading the line than the word "null".
	 */
	static String nameOf(GeTransaction tx)
	{
		return tx.itemName == null || tx.itemName.isEmpty() ? "Item " + tx.itemId : tx.itemName;
	}

	// ----------------------------------------------------------- journal
	//
	// The words a journal row is made of. Static and stateless, so the
	// tab that draws them and the tests that pin them share one copy.

/** "+1.20M from 12 flips · 75.0% wins · 45.0K gp/h", or a quiet week. */
	static String summarise(Analytics week)
	{
		if (week.getCompletedFlips() == 0)
		{
			return "No flips closed this week.";
		}
		return signed(week.getRealisedProfit()) + " from " + week.getCompletedFlips()
			+ (week.getCompletedFlips() == 1 ? " flip" : " flips")
			+ " · " + pct(week.getWinRate()) + " wins"
			+ (week.getGpPerHour() != 0 ? " · " + gp(week.getGpPerHour()) + " gp/h" : "");
	}

	/**
	 * The positions as one string, for telling a redraw that would change
	 * something from one that would not.
	 *
	 * <p>Everything a card shows is in it. A figure left out is a figure that
	 * can move on the server without the card following it -- a price that
	 * never changes again, a lot that stays marked stale after it sold --
	 * which is a good deal worse than a redraw that was not needed.
	 */
	static String signatureOf(List<Position> positions)
	{
		final StringBuilder out = new StringBuilder(positions.size() * 48);
		for (Position p : positions)
		{
			out.append(p.getId()).append(SEP).append(p.getItemId()).append(SEP)
				.append(p.getItemName()).append(SEP).append(p.getRemainingQty()).append(SEP)
				.append(p.getHoursHeld()).append(SEP).append(p.getBuyPrice()).append(SEP)
				.append(p.getCurrentBuy()).append(SEP).append(p.getCurrentSell()).append(SEP)
				.append(p.getUnrealisedPnl()).append(SEP).append(p.getUnrealisedRoi()).append(SEP)
				.append(p.getBreakEvenSell()).append(SEP).append(p.isStale()).append(ROW);
		}
		return out.toString();
	}

	/**
	 * What the Close box starts with in its price field: the price a patient
	 * sale lists at, or what an instant one would get if the site has no
	 * listing price. Zero when it has neither, which leaves the field empty
	 * rather than suggesting a sale at nothing.
	 */
	static long suggestedSalePrice(Position p)
	{
		return p.getCurrentBuy() > 0 ? p.getCurrentBuy() : p.getCurrentSell();
	}

	/**
	 * "1,480,000", "1480000", "1.48m" and "1.5k" all read as gp; anything that
	 * is not a number reads as 0, and so does one too big to be one.
	 */
	static long parseGp(String text)
	{
		final String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
		if (s.isEmpty())
		{
			return 0;
		}
		try
		{
			long multiplier = 1;
			String digits = s;
			if (s.endsWith("k"))
			{
				multiplier = 1_000;
				digits = s.substring(0, s.length() - 1);
			}
			else if (s.endsWith("m"))
			{
				multiplier = 1_000_000;
				digits = s.substring(0, s.length() - 1);
			}
			else if (s.endsWith("b"))
			{
				multiplier = 1_000_000_000;
				digits = s.substring(0, s.length() - 1);
			}
			final double gp = Double.parseDouble(digits) * multiplier;
			// Double.parseDouble reads more than a price box has any business
			// accepting: "Infinity" is a number to it, and so is a run of
			// digits long enough to overflow to one. Math.round turns either
			// into Long.MAX_VALUE, and the Close box would post that as the
			// price a position sold at. Nothing readable is the right answer,
			// and the box already says a sale price is needed.
			return Double.isFinite(gp) ? Math.round(gp) : 0;
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/**
	 * "Bought 1,480,000 · Sell 1,520,000 (now 1,500,000)": what was paid, the
	 * price a sale lists at, and what an instant sale would get. The site's
	 * profit figure is worked on the instant figure, the cautious one.
	 */
	static String positionPrices(Position p)
	{
		final StringBuilder out = new StringBuilder("Bought " + exact(p.getBuyPrice()));
		if (p.getCurrentBuy() > 0)
		{
			out.append(" · Sell ").append(exact(p.getCurrentBuy()));
			if (p.getCurrentSell() > 0 && p.getCurrentSell() != p.getCurrentBuy())
			{
				out.append(" (now ").append(exact(p.getCurrentSell())).append(")");
			}
		}
		else if (p.getCurrentSell() > 0)
		{
			out.append(" · Sell ").append(exact(p.getCurrentSell()));
		}
		return out.toString();
	}

	// -------------------------------------------------------- watchlists
	//
	// The words a watchlist row is made of. Static and stateless, so the
	// tab that draws them and the tests that pin them share one copy.

/**
	 * "Buy 1,480,000 · Sell 1,520,000". To the coin, because this is the
	 * number that gets typed into the offer.
	 */
	static String pricesLine(Quote q)
	{
		return "Buy " + exact(q.getBuyAt()) + " · Sell " + exact(q.getSellAt());
	}

	/** "Margin +9,600 · ROI 0.7%" */
	static String marginLine(Quote q)
	{
		return "Margin " + signedExact(q.getNetMargin()) + " · ROI " + pct(q.getRoi());
	}

	/** "Limit 70 · +2.10M per limit · 1.2K traded/24h", leaving out what is unknown. */
	static String limitLine(WatchedItem item)
	{
		final Quote q = item.quote;
		final StringBuilder out = new StringBuilder();
		final int limit = q != null && q.getBuyLimit() > 0 ? q.getBuyLimit() : item.limit;
		if (limit > 0)
		{
			out.append("Limit ").append(limit);
		}
		if (q != null && q.getProfitPerLimit() != 0)
		{
			out.append(out.length() > 0 ? " · " : "").append(signed(q.getProfitPerLimit())).append(" per limit");
		}
		if (q != null && q.getVolume24h() > 0)
		{
			out.append(out.length() > 0 ? " · " : "").append(count(q.getVolume24h())).append(" traded/24h");
		}
		return out.toString();
	}

	/** "1.50M · limit 70 · alch 1.20M", leaving out what is unknown. The fallback when the site has no quote. */
	static String facts(WatchedItem item)
	{
		final StringBuilder out = new StringBuilder();
		if (item.price > 0)
		{
			out.append(gp(item.price));
		}
		if (item.limit > 0)
		{
			out.append(out.length() > 0 ? " · " : "").append("limit ").append(item.limit);
		}
		if (item.alch > 0)
		{
			out.append(out.length() > 0 ? " · " : "").append("alch ").append(gp(item.alch));
		}
		return out.length() == 0 ? "No price known" : out.toString();
	}

	// ---------------------------------------------------------------- helpers

	static JPanel card()
	{
		final JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(5, CARD_PADDING, 5, CARD_PADDING));
		return card;
	}

	/**
	 * Puts a notice on a tab, with the clock that takes it off again.
	 *
	 * <p>The clock is one-shot and restarted from here rather than left
	 * running, so a second notice gets the full {@link #NOTICE_SECONDS} rather
	 * than whatever was left of the first one's.
	 *
	 * @param text null to clear the notice and stop the clock
	 */
	static void setNotice(JLabel label, Timer clock, @Nullable String text, Color colour)
	{
		setWrappedText(label, text);
		label.setForeground(colour);
		label.setVisible(text != null);
		clock.setRepeats(false);
		clock.stop();
		if (text != null)
		{
			clock.start();
		}
	}

	static JLabel small(String text)
	{
		final JLabel label = new JLabel();
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		setWrappedText(label, text);
		return label;
	}

	/**
	 * What Swing multiplies a CSS pixel by when it lays out an HTML label.
	 *
	 * <p>It is not one. A label told to wrap at 150 lays itself out 195 wide,
	 * and 1.3 exactly, at every width, on a 96 dpi screen. So a width written
	 * into the HTML is not a width on the screen, and the two have to be kept
	 * apart or the sums come out a third too wide.
	 */
	static final double CSS_PIXEL = 1.3;

	/**
	 * How much room a line of text has, in real pixels, worked out from the
	 * width RuneLite gives a side panel rather than guessed.
	 *
	 * <p>A label only wraps if it is told a width; left to itself it asks for
	 * the width of its longest line and is painted cut off at the edge of its
	 * row instead. So the figure matters in both directions, and neither shows
	 * up in a test that reads the text back, because a cut-off line and a whole
	 * one hold the same string.
	 *
	 * <p>The narrower of the two rows is the safe one to use for both, since a
	 * line that could have run wider only costs a wrap, while one that runs
	 * wider than its row loses its end -- and these lines end in a price.
	 */
	static final int TEXT_WIDTH =
		PluginPanel.PANEL_WIDTH - 2 * PANEL_PADDING - 2 * CARD_PADDING;

	/** And beside a card's sprite, which takes its width off the front. */
	static final int TITLE_WIDTH = TEXT_WIDTH - ICON_WIDTH - ICON_GAP;

	/** Sets a label's text, wrapping it only if it will not fit its row. */
	static void setWrappedText(JLabel label, String text)
	{
		setWrappedText(label, text, TEXT_WIDTH);
	}

	/** The same, for the title that sits beside a card's sprite. */
	static void setWrappedTextBesideIcon(JLabel label, String text)
	{
		setWrappedText(label, text, TITLE_WIDTH);
	}

	/**
	 * Sets a label's text, and only asks for wrapping if the text needs it.
	 *
	 * <p>Wrapping means HTML, and an HTML label is not a cheap thing: the text
	 * becomes a document that has to be parsed and laid out, which measures at
	 * about half a millisecond against twenty microseconds for a plain label.
	 * Twenty-six times the work, and nearly every line here is one line anyway
	 * -- a price, a time, a margin, a name. Asking for a document to hold them
	 * put a sixth of a second on the Swing thread each time a full watchlist of
	 * fifty redrew, and that is every thirty seconds the sidebar is open.
	 *
	 * <p>So the text is measured first, and only the lines that really run past
	 * their row pay for it. The label's font must already be set, since that is
	 * what the text is measured in.
	 */
	static void setWrappedText(JLabel label, String text, int pixels)
	{
		final String plain = text == null ? "" : text;
		final boolean fits = label.getFontMetrics(label.getFont()).stringWidth(plain) <= pixels;
		// Swing reads a label's text as markup if, and only if, it opens with
		// a tag. Server messages and item names are not ours to trust, so
		// anything that opens with a bracket goes the long way round and is
		// escaped, whether or not it would have fitted.
		label.setText(fits && !plain.startsWith("<") ? plain : wrap(plain, pixels));
	}

	/**
	 * @param pixels how much room the line has on screen, not the figure to
	 *               write into the HTML -- see {@link #CSS_PIXEL}
	 */
	static String wrap(String text, int pixels)
	{
		final int css = (int) (pixels / CSS_PIXEL);
		return "<html><body style='width:" + css + "px'>" + escape(text) + "</body></html>";
	}

	// ---------------------------------------------------------- test seams
	//
	// Package-private and used only by the tests. The alternative is asserting
	// against the Swing component tree, which breaks whenever the layout is
	// touched and tests the wrong thing.

	/** The recent trades as one line each, the way the buffer list shows them. */
	List<String> recentForTest()
	{
		return trades.recentForTest();
	}

	List<String> pendingForTest()
	{
		return activity.pendingForTest();
	}

	/** The watched item ids as rendered, in order. */
	List<Integer> watchlistForTest()
	{
		return watchlist.watchedForTest();
	}

	/** The live-offer line of a watched item's card, or null if it has none. */
	@Nullable
	String watchlistOfferForTest(int itemId)
	{
		return watchlist.offerForTest(itemId);
	}

	@Nullable
	String pausedForTest()
	{
		return paused;
	}

	/** The site's price line for a watched item, or null if it has no quote. */
	@Nullable
	String watchlistPricesForTest(int itemId)
	{
		return watchlist.pricesForTest(itemId);
	}

	/** The open positions' item ids as rendered, in order. */
	List<Integer> positionsForTest()
	{
		return journal.positionsForTest();
	}

	String journalSummaryForTest()
	{
		return journal.summaryForTest();
	}

	String journalNoticeForTest()
	{
		return journal.noticeForTest();
	}

	@Nullable
	String journalProblemForTest()
	{
		return journal.problemForTest();
	}

	/** The "Recorded this session" line, which a new session starts over. */
	String recordedTextForTest()
	{
		return activity.recordedTextForTest();
	}

	String lastSyncTextForTest()
	{
		return activity.lastSyncTextForTest();
	}

	String statusTextForTest()
	{
		return account.statusTextForTest();
	}

	String subscriptionTextForTest()
	{
		return account.subscriptionTextForTest();
	}

	String activityNoticeForTest()
	{
		return activity.noticeForTest();
	}

	/** Whether a notice is still up, i.e. it has not been cleared or expired. */
	boolean activityNoticeShowingForTest()
	{
		return activity.noticeShowingForTest();
	}

	boolean watchlistNoticeShowingForTest()
	{
		return watchlist.noticeShowingForTest();
	}

	/** Whether the notice is still counting down to clearing itself. */
	boolean activityNoticeTimerArmedForTest()
	{
		return activity.noticeTimerForTest().isRunning();
	}

	/** The three notice clocks, one per tab that has one. */
	private Timer[] noticeTimers()
	{
		return new Timer[]{
			activity.noticeTimerForTest(),
			watchlist.noticeTimerForTest(),
			journal.noticeTimerForTest(),
		};
	}

	/** How many of the three notice clocks are running. */
	int armedNoticeTimersForTest()
	{
		int armed = 0;
		for (Timer timer : noticeTimers())
		{
			if (timer.isRunning())
			{
				armed++;
			}
		}
		return armed;
	}

	/** Fires the notice timers now, as if the interval had passed. */
	void expireNoticesForTest()
	{
		for (Timer timer : noticeTimers())
		{
			if (!timer.isRunning())
			{
				continue;
			}
			for (java.awt.event.ActionListener listener : timer.getActionListeners())
			{
				listener.actionPerformed(null);
			}
			// Swing stops a one-shot timer when it fires and leaves a repeating
			// one going, so this has to as well. Stopping every timer here
			// regardless left the two indistinguishable, and a test written
			// against this could not have told a clock that clears a notice
			// once from one that goes on waking the Swing thread every twenty
			// seconds for the life of the client.
			if (!timer.isRepeats())
			{
				timer.stop();
			}
		}
	}

	String watchlistNoticeForTest()
	{
		return watchlist.noticeForTest();
	}

	@Nullable
	String watchlistProblemForTest()
	{
		return watchlist.problemForTest();
	}

	@Nullable
	String activityProblemForTest()
	{
		return trades.problemForTest();
	}

	/** The list a tab draws its rows into. */
	private JPanel listOf(String tab)
	{
		switch (tab)
		{
			case "Trades":
				return trades.listForTest();
			case "Journal":
				return journal.listForTest();
			case "Watchlists":
				return watchlist.listForTest();
			default:
				return activity.listForTest();
		}
	}

	/** How many rows a tab's list has actually built. */
	int drawnRowsForTest(String tab)
	{
		return listOf(tab).getComponentCount();
	}

	/**
	 * A tab's cards as drawn, so a test can read what is on one and tell a
	 * rebuild from a redraw that changed nothing.
	 */
	Component[] cardsForTest(String tab)
	{
		switch (tab)
		{
			case "Trades":
				return trades.listForTest().getComponents();
			case "Watchlists":
				return watchlist.listForTest().getComponents();
			default:
				return journal.listForTest().getComponents();
		}
	}

	/** The tab strip's preferred width, to check it fits the sidebar. */
	int tabStripWidthForTest()
	{
		return tabs.getPreferredSize().width;
	}

	/** Which tab is showing. */
	String selectedTabForTest()
	{
		if (tradesTab.isSelected())
		{
			return "Trades";
		}
		if (journalTab.isSelected())
		{
			return "Journal";
		}
		if (watchlistTab.isSelected())
		{
			return "Watchlists";
		}
		if (accountTab.isSelected())
		{
			return "Account";
		}
		return "Activity";
	}

	void selectTabForTest(String name)
	{
		switch (name)
		{
			case "Trades":
				tabs.select(tradesTab);
				break;
			case "Journal":
				tabs.select(journalTab);
				break;
			case "Watchlists":
				tabs.select(watchlistTab);
				break;
			case "Account":
				tabs.select(accountTab);
				break;
			default:
				tabs.select(activityTab);
				break;
		}
	}

	/** Selects by id the way a user clicking the combo box would. */
	void setSelectedForTest(String id)
	{
		account.setSelectedForTest(id);
	}

	/** The user pressing "Send now", listener and all. */
	void pressSendNowForTest()
	{
		activity.pressSendNowForTest();
	}

	/** The user pressing "Try set-aside trades again", listener and all. */
	void pressRetrySetAsideForTest()
	{
		activity.pressRetrySetAsideForTest();
	}

	boolean retryOfferedForTest()
	{
		return activity.retryOfferedForTest();
	}

	String setAsideTextForTest()
	{
		return activity.setAsideTextForTest();
	}

	/** The user picking a watchlist from the dropdown, listener and all. */
	void setSelectedWatchlistForTest(String id)
	{
		watchlist.setSelectedForTest(id);
	}

	/**
	 * Short gp, the way the game and the site both write it.
	 *
	 * <p>Locale.ROOT, not the default locale. String.format follows the JVM's
	 * locale, so on a machine set to most of Europe this produced "1,50M" --
	 * a decimal comma reads as a thousands separator to an English-speaking
	 * player, which turns 1.5M into an apparent 150M at a glance.
	 */
	static String gp(long amount)
	{
		final long abs = Math.abs(amount);
		if (abs >= 1_000_000_000L)
		{
			return String.format(Locale.ROOT, "%.2fB", amount / 1_000_000_000d);
		}
		if (abs >= 1_000_000L)
		{
			return String.format(Locale.ROOT, "%.2fM", amount / 1_000_000d);
		}
		if (abs >= 1_000L)
		{
			return String.format(Locale.ROOT, "%.1fK", amount / 1_000d);
		}
		return amount + "gp";
	}

	/** gp with its sign always shown: "+30.0K", "-1.2K", "0gp". */
	static String signed(long amount)
	{
		return amount > 0 ? "+" + gp(amount) : gp(amount);
	}

	/** gp to the coin, with thousands separators: "1,480,000". For numbers that get typed. */
	static String exact(long amount)
	{
		return String.format(Locale.ROOT, "%,d", amount);
	}

	/** Exact gp with its sign always shown: "+9,600", "-500", "0". */
	static String signedExact(long amount)
	{
		return amount > 0 ? "+" + exact(amount) : exact(amount);
	}

	/** A count, not money: "1.2K", "340". */
	static String count(long n)
	{
		if (n >= 1_000_000L)
		{
			return String.format(Locale.ROOT, "%.1fM", n / 1_000_000d);
		}
		if (n >= 1_000L)
		{
			return String.format(Locale.ROOT, "%.1fK", n / 1_000d);
		}
		return Long.toString(n);
	}

	/** A fraction as a percentage: 0.0213 is "2.1%". */
	static String pct(double fraction)
	{
		return String.format(Locale.ROOT, "%.1f%%", fraction * 100d);
	}

	/**
	 * "5h", "2d 3h", "40m".
	 *
	 * <p>Rounded once, up front, and the days and hours taken from that one
	 * figure. Rounding the remainder separately produced "2d 24h" for anything
	 * that landed in the last half hour of a day -- a duration that reads as
	 * nonsense next to the "3d 0h" it is.
	 */
	static String hours(double hours)
	{
		final long minutes = Math.round(hours * 60);
		if (minutes < 60)
		{
			return minutes + "m";
		}
		final long total = Math.round(hours);
		if (total < 48)
		{
			return total + "h";
		}
		return (total / 24) + "d " + (total % 24) + "h";
	}

	/**
	 * The labels render HTML so they can wrap, which means server messages and
	 * item names have to be escaped rather than interpreted.
	 */
	static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
