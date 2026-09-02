package com.flippingrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
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
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	static final int RECENT_SHOWN = 8;

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
		final FlippingRsApi.Quote quote;

		WatchedItem(int itemId, String name, @Nullable AsyncBufferedImage image,
			int price, int limit, int alch, @Nullable String offer, @Nullable FlippingRsApi.Quote quote)
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

	/**
	 * A tab with less padding and the narrower font, so five fit in a
	 * 205-pixel sidebar in two rows. RuneLite's own tab re-applies its wide
	 * border on every select and unselect, hence the overrides.
	 */
	private static class Tab extends MaterialTab
	{
		private static final Border SELECTED = BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(4, 3, 3, 3));
		private static final Border UNSELECTED = BorderFactory.createEmptyBorder(4, 3, 4, 3);

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
			setBorder(SELECTED);
			return selected;
		}

		@Override
		public void unselect()
		{
			super.unselect();
			setBorder(UNSELECTED);
		}
	}

	// Activity
	private final JLabel recorded = new JLabel();
	private final JLabel queued = new JLabel();
	private final JLabel lastSync = new JLabel();
	private final JLabel activityNotice = new JLabel();
	private final JButton syncNow = new JButton("Send now");
	private final JPanel pendingList = new JPanel();
	private final List<String> pending = new ArrayList<>();

	// Trades
	private final JPanel recentList = new JPanel();
	private final List<String> recent = new ArrayList<>();
	@Nullable
	private String recentProblem;

	// Journal
	private final JLabel journalSummary = new JLabel();
	private final JLabel journalOpen = new JLabel();
	private final JPanel positionList = new JPanel();
	private List<FlippingRsApi.Position> positions = new ArrayList<>();
	private boolean journalLoaded;
	@Nullable
	private String journalProblem;

	// Watchlists
	private final JComboBox<FlippingRsApi.Watchlist> watchlists = new JComboBox<>();
	private final JLabel watchlistNotice = new JLabel();
	private final JPanel watchlistItems = new JPanel();
	private final JButton findFlips = new JButton("Find flips");
	private List<WatchedItem> watched = new ArrayList<>();
	/** The live-offer line of each card, so one fill can update one line. */
	private final Map<Integer, JLabel> offerLines = new HashMap<>();
	@Nullable
	private String watchlistProblem;

	/**
	 * Set while the plugin is not reading from the server at all: recording
	 * off, or no key. The tabs that show the server's data show this instead
	 * of stale rows. Cleared by the next data that arrives.
	 */
	@Nullable
	private String paused;

	// Account
	private final JLabel status = new JLabel();
	private final JLabel subscription = new JLabel();
	private final JComboBox<FlippingRsApi.GameAccount> accounts = new JComboBox<>();
	private final JButton reconnect = new JButton("Reconnect");

	private final MaterialTabGroup tabs;
	private final MaterialTab activityTab;
	private final MaterialTab tradesTab;
	private final MaterialTab journalTab;
	private final MaterialTab watchlistTab;
	private final MaterialTab accountTab;

	/** Set by the plugin; fires when the user picks a different game account. */
	private Runnable onAccountChosen = () -> {
	};
	private Runnable onWatchlistChosen = () -> {
	};
	private IntConsumer onOpenItem = id -> {
	};
	private IntConsumer onRemoveItem = id -> {
	};

	public FlippingRsPanel()
	{
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		final JPanel top = column();
		top.add(header("FlippingRS"));
		top.add(Box.createVerticalStrut(6));

		final JPanel display = new JPanel();
		tabs = new MaterialTabGroup(display);
		activityTab = new Tab("Activity", tabs, activityTab());
		tradesTab = new Tab("Trades", tabs, tradesTab());
		journalTab = new Tab("Journal", tabs, journalTab());
		watchlistTab = new Tab("Watchlists", tabs, watchlistTab());
		accountTab = new Tab("Account", tabs, accountTab());
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
		redrawPending();
		redrawRecent();
		redrawJournal();
		setWatchlists(new ArrayList<>(), null);
		redrawWatchlist();
		tabs.select(activityTab);
	}

	private JPanel activityTab()
	{
		final JPanel body = column();
		final JPanel stats = new JPanel(new GridLayout(0, 1, 0, 2));
		for (JLabel label : new JLabel[]{recorded, queued, lastSync})
		{
			label.setFont(FontManager.getRunescapeSmallFont());
			stats.add(label);
		}
		stats.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(stats);
		body.add(Box.createVerticalStrut(6));

		activityNotice.setFont(FontManager.getRunescapeSmallFont());
		activityNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(activityNotice);
		body.add(Box.createVerticalStrut(6));

		syncNow.setToolTipText("Send your waiting trades now instead of at the next scheduled time.");
		syncNow.setAlignmentX(Component.LEFT_ALIGNMENT);
		syncNow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(syncNow);
		body.add(Box.createVerticalStrut(10));

		body.add(header("Waiting to send"));
		body.add(Box.createVerticalStrut(4));
		pendingList.setLayout(new BoxLayout(pendingList, BoxLayout.Y_AXIS));
		pendingList.setAlignmentX(Component.LEFT_ALIGNMENT);
		pendingList.setToolTipText("Trades recorded here that your journal hasn't confirmed yet.");
		body.add(pendingList);
		return body;
	}

	private JPanel tradesTab()
	{
		final JPanel body = column();
		recentList.setLayout(new BoxLayout(recentList, BoxLayout.Y_AXIS));
		recentList.setAlignmentX(Component.LEFT_ALIGNMENT);
		recentList.setToolTipText("Your most recent trades, as your journal has them.");
		body.add(recentList);
		return body;
	}

	private JPanel journalTab()
	{
		final JPanel body = column();
		body.add(header("Last 7 days"));
		body.add(Box.createVerticalStrut(4));
		journalSummary.setFont(FontManager.getRunescapeSmallFont());
		journalSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		journalSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(journalSummary);
		body.add(Box.createVerticalStrut(10));

		body.add(header("Open positions"));
		body.add(Box.createVerticalStrut(4));
		journalOpen.setFont(FontManager.getRunescapeSmallFont());
		journalOpen.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		journalOpen.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(journalOpen);
		body.add(Box.createVerticalStrut(4));
		positionList.setLayout(new BoxLayout(positionList, BoxLayout.Y_AXIS));
		positionList.setAlignmentX(Component.LEFT_ALIGNMENT);
		positionList.setToolTipText("What you're holding, what it cost you, and what it's worth right now.");
		body.add(positionList);
		return body;
	}

	private JPanel watchlistTab()
	{
		final JPanel body = column();
		watchlists.setAlignmentX(Component.LEFT_ALIGNMENT);
		watchlists.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		watchlists.setToolTipText("Which of your flippingrs.com watchlists to show. Right-clicking an item adds it here.");
		watchlists.addActionListener(e -> onWatchlistChosen.run());
		body.add(watchlists);
		body.add(Box.createVerticalStrut(4));
		watchlistNotice.setFont(FontManager.getRunescapeSmallFont());
		watchlistNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(watchlistNotice);
		body.add(Box.createVerticalStrut(4));
		watchlistItems.setLayout(new BoxLayout(watchlistItems, BoxLayout.Y_AXIS));
		watchlistItems.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(watchlistItems);
		body.add(Box.createVerticalStrut(8));
		findFlips.setToolTipText("Open the flip finder in your browser.");
		findFlips.setAlignmentX(Component.LEFT_ALIGNMENT);
		findFlips.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(findFlips);
		return body;
	}

	private JPanel accountTab()
	{
		final JPanel body = column();
		body.add(header("Connection"));
		body.add(Box.createVerticalStrut(4));
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(status);
		body.add(Box.createVerticalStrut(4));
		subscription.setFont(FontManager.getRunescapeSmallFont());
		subscription.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		subscription.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(subscription);
		body.add(Box.createVerticalStrut(10));

		body.add(header("Journal"));
		body.add(Box.createVerticalStrut(4));
		accounts.setAlignmentX(Component.LEFT_ALIGNMENT);
		accounts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		accounts.setToolTipText("Which journal this character's trades go into. Each character remembers its own "
			+ "choice, so an alt can have its own journal.");
		accounts.addActionListener(e -> onAccountChosen.run());
		body.add(accounts);
		body.add(Box.createVerticalStrut(10));

		reconnect.setToolTipText("Check your API key again and reload everything from flippingrs.com.");
		reconnect.setAlignmentX(Component.LEFT_ALIGNMENT);
		reconnect.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(reconnect);
		return body;
	}

	private static JPanel column()
	{
		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		return body;
	}

	void onSyncNow(Runnable action)
	{
		syncNow.addActionListener(e -> action.run());
	}

	void onReconnect(Runnable action)
	{
		reconnect.addActionListener(e -> action.run());
	}

	void onAccountChosen(Runnable action)
	{
		onAccountChosen = action;
	}

	void onWatchlistChosen(Runnable action)
	{
		onWatchlistChosen = action;
	}

	void onOpenItem(IntConsumer action)
	{
		onOpenItem = action;
	}

	void onRemoveItem(IntConsumer action)
	{
		onRemoveItem = action;
	}

	void onFindFlips(Runnable action)
	{
		findFlips.addActionListener(e -> action.run());
	}

	private static JLabel header(String text)
	{
		final JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	// ---------------------------------------------------------------- account

	/** The connection status, on the Account tab. */
	void setStatus(String text, Color colour)
	{
		status.setText(wrap(text));
		status.setForeground(colour);
	}

	/** The plan the key's owner is on, in the server's words, or null if not known. */
	void setSubscription(@Nullable String text)
	{
		subscription.setText(wrap(text == null ? "Plan: not checked yet" : text));
	}

	/**
	 * Nothing is being read from the server, for the reason given. Trades,
	 * Journal and Watchlists show the reason instead of whatever they last
	 * held, and the plan line is cleared.
	 */
	void setPaused(String why)
	{
		paused = why;
		recent.clear();
		recentProblem = null;
		positions = new ArrayList<>();
		journalLoaded = false;
		journalProblem = null;
		watched = new ArrayList<>();
		watchlistProblem = null;
		setWatchlists(new ArrayList<>(), null);
		setSubscription(null);
		redrawRecent();
		redrawJournal();
		redrawWatchlist();
	}

	/**
	 * Replaces the account list, restoring the current selection if it survives.
	 *
	 * <p>With nothing remembered ({@code selectedId} null) the server's default
	 * is selected, or failing that the first entry, so a fresh account has
	 * something sensible to adopt. With something remembered that is no longer
	 * in the list, nothing is selected: showing the first entry there would
	 * have the panel naming a journal the plugin is not filing under.
	 *
	 * <p>The listener is detached while the model is swapped: repopulating a
	 * combo box fires a selection event, and letting that through would look
	 * like the user re-picking the account and write the setting back on every
	 * reconnect.
	 */
	void setAccounts(List<FlippingRsApi.GameAccount> available, @Nullable String selectedId)
	{
		final Runnable listener = onAccountChosen;
		onAccountChosen = () -> {
		};
		try
		{
			final DefaultComboBoxModel<FlippingRsApi.GameAccount> model = new DefaultComboBoxModel<>();
			FlippingRsApi.GameAccount remembered = null;
			FlippingRsApi.GameAccount fallback = null;
			for (FlippingRsApi.GameAccount account : available)
			{
				if (account == null || account.id == null)
				{
					continue;
				}
				model.addElement(account);
				if (account.id.equals(selectedId))
				{
					remembered = account;
				}
				if (fallback == null && account.isDefault)
				{
					fallback = account;
				}
			}
			accounts.setModel(model);
			if (remembered != null)
			{
				accounts.setSelectedItem(remembered);
			}
			else if (selectedId != null)
			{
				// Remembered, but gone. The default is not a stand-in for it.
				accounts.setSelectedIndex(-1);
			}
			else if (fallback != null)
			{
				accounts.setSelectedItem(fallback);
			}
			// Otherwise the model has already selected the first entry.
			accounts.setEnabled(model.getSize() > 0);
		}
		finally
		{
			onAccountChosen = listener;
		}
	}

	@Nullable
	String selectedAccountId()
	{
		final Object selected = accounts.getSelectedItem();
		return selected == null ? null : ((FlippingRsApi.GameAccount) selected).id;
	}

	// --------------------------------------------------------------- activity

	void setCounts(int recordedCount, int queuedCount)
	{
		recorded.setText("Recorded this session: " + recordedCount);
		queued.setText("Waiting to send: " + queuedCount);
		queued.setForeground(queuedCount > 0 ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	void setLastSync(@Nullable Instant at, @Nullable String problem)
	{
		if (problem != null)
		{
			lastSync.setText(wrap("Last send failed: " + problem));
			lastSync.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}
		lastSync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastSync.setText(at == null ? "Last sent: never" : "Last sent: " + TIME.format(at));
	}

	/** A note about capturing or sending: an adopted offer, a refused batch. Null clears it. */
	void setActivityNotice(@Nullable String text, Color colour)
	{
		activityNotice.setText(text == null ? "" : wrap(text));
		activityNotice.setForeground(colour);
		activityNotice.setVisible(text != null);
	}

	/** Replaces the list of fills still buffered, newest first. */
	void setPending(List<GeTransaction> newestFirst)
	{
		pending.clear();
		for (GeTransaction tx : newestFirst)
		{
			if (pending.size() >= RECENT_SHOWN)
			{
				break;
			}
			pending.add(line(tx));
		}
		redrawPending();
	}

	private void redrawPending()
	{
		pendingList.removeAll();
		if (pending.isEmpty())
		{
			pendingList.add(small("Nothing waiting to send."));
		}
		for (String line : pending)
		{
			pendingList.add(small(line));
		}
		pendingList.revalidate();
		pendingList.repaint();
	}

	// ----------------------------------------------------------------- trades

	/**
	 * Replaces the recent trades with what the server recorded, newest first.
	 * Only the first {@link #RECENT_SHOWN} are drawn.
	 */
	void setActivity(List<GeTransaction> newestFirst)
	{
		paused = null;
		recentProblem = null;
		recent.clear();
		for (GeTransaction tx : newestFirst)
		{
			if (recent.size() >= RECENT_SHOWN)
			{
				break;
			}
			recent.add(line(tx));
		}
		redrawRecent();
	}

	/** The recent trades could not be read. Shown in the tab itself. */
	void setActivityProblem(String why)
	{
		recentProblem = why;
		redrawRecent();
	}

	private void redrawRecent()
	{
		recentList.removeAll();
		if (paused != null)
		{
			recentList.add(small(paused));
		}
		else if (recentProblem != null)
		{
			final JLabel problem = small("Couldn't load your recent trades: " + recentProblem);
			problem.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			recentList.add(problem);
		}
		else if (recent.isEmpty())
		{
			recentList.add(small("No trades recorded yet."));
		}
		else
		{
			for (String line : recent)
			{
				recentList.add(small(line));
			}
		}
		recentList.revalidate();
		recentList.repaint();
	}

	/** One fill as a line: its own time, or "recovered" when it has none, then side, quantity, item and gp. */
	private static String line(GeTransaction tx)
	{
		return (tx.occurredAt == null ? "recovered" : TIME.format(occurredAt(tx))) + "  "
			+ ("buy".equals(tx.side) ? "Bought " : "Sold ")
			+ tx.quantity + " x " + tx.itemName
			+ " for " + gp(tx.grossValue) + (tx.estimated ? " (approx)" : "");
	}

	// ---------------------------------------------------------------- journal

	/** The journal's week and its open positions, as the server has them. */
	void setJournal(FlippingRsApi.Analytics week, FlippingRsApi.Positions open)
	{
		paused = null;
		journalProblem = null;
		journalLoaded = true;
		journalSummary.setText(wrap(summarise(week)));
		journalSummary.setForeground(week.getRealisedProfit() < 0
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		final FlippingRsApi.Positions.Summary totals = open.getSummary();
		positions = open.getPositions();
		journalOpen.setText(wrap(positions.isEmpty()
			? "No open positions."
			: totals.openPositions + " open · cost " + gp(totals.costBasis) + " · value " + gp(totals.marketValue)
			+ " · P&L " + signed(totals.unrealisedPnl)
			+ (totals.marketDataAvailable ? "" : " (no market data)")));
		redrawJournal();
	}

	/** The journal could not be read. Shown in the tab itself. */
	void setJournalProblem(String why)
	{
		journalProblem = why;
		journalLoaded = false;
		positions = new ArrayList<>();
		redrawJournal();
	}

	/** "+1.20M from 12 flips · 75.0% wins · 45.0K gp/h", or a quiet week. */
	static String summarise(FlippingRsApi.Analytics week)
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

	private void redrawJournal()
	{
		positionList.removeAll();
		if (paused != null)
		{
			journalSummary.setText(wrap(paused));
			journalSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			journalOpen.setText("");
		}
		else if (journalProblem != null)
		{
			journalSummary.setText(wrap("Couldn't load your journal: " + journalProblem));
			journalSummary.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			journalOpen.setText("");
		}
		else if (!journalLoaded)
		{
			journalSummary.setText(wrap("Not loaded yet."));
			journalSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			journalOpen.setText("");
		}
		for (FlippingRsApi.Position position : positions)
		{
			positionList.add(positionRow(position));
			positionList.add(Box.createVerticalStrut(4));
		}
		positionList.revalidate();
		positionList.repaint();
	}

	/** One open position: what is held, what it cost, what it is worth now. */
	private JPanel positionRow(FlippingRsApi.Position p)
	{
		final JPanel card = card();
		final JLabel title = new JLabel(wrap(p.getItemName().isEmpty() ? "Item " + p.getItemId() : p.getItemName()));
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(title);
		card.add(small(p.getRemainingQty() + " left · bought at " + gp(p.getBuyPrice())
			+ " · held " + hours(p.getHoursHeld())));
		final JLabel now = small("Now " + gp(p.getCurrentSell()) + " · P&L " + signed(p.getUnrealisedPnl())
			+ " (" + pct(p.getUnrealisedRoi()) + ")");
		now.setForeground(p.getUnrealisedPnl() < 0 ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR);
		card.add(now);
		if (p.getBreakEvenSell() > 0)
		{
			card.add(small("Break even at " + gp(p.getBreakEvenSell())));
		}
		if (p.isStale())
		{
			final JLabel stale = small("Stale: you've held this much longer than this item usually takes to flip.");
			stale.setForeground(ColorScheme.BRAND_ORANGE);
			card.add(stale);
		}
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	// ------------------------------------------------------------- watchlists

	/**
	 * Replaces the watchlist picker. The selection is the plugin's to decide,
	 * because it is also the plugin's to remember; an id not in the list
	 * leaves the first entry selected, since any board is a fine place to add
	 * to and the picker says which.
	 */
	void setWatchlists(List<FlippingRsApi.Watchlist> available, @Nullable String selectedId)
	{
		final Runnable listener = onWatchlistChosen;
		onWatchlistChosen = () -> {
		};
		try
		{
			final DefaultComboBoxModel<FlippingRsApi.Watchlist> model = new DefaultComboBoxModel<>();
			FlippingRsApi.Watchlist select = null;
			for (FlippingRsApi.Watchlist watchlist : available)
			{
				if (watchlist == null || watchlist.id == null)
				{
					continue;
				}
				model.addElement(watchlist);
				if (watchlist.id.equals(selectedId))
				{
					select = watchlist;
				}
			}
			watchlists.setModel(model);
			if (select != null)
			{
				watchlists.setSelectedItem(select);
			}
			watchlists.setEnabled(model.getSize() > 0);
		}
		finally
		{
			onWatchlistChosen = listener;
		}
	}

	@Nullable
	String selectedWatchlistId()
	{
		final Object selected = watchlists.getSelectedItem();
		return selected == null ? null : ((FlippingRsApi.Watchlist) selected).id;
	}

	/** Replaces the watchlist's rows, in the server's order. */
	void setWatchlistItems(List<WatchedItem> items)
	{
		paused = null;
		watchlistProblem = null;
		watched = new ArrayList<>(items);
		redrawWatchlist();
	}

	/**
	 * Updates one card's live-offer line, for a fill on a watched item.
	 *
	 * <p>Only the line is touched when the card already has one; the card is
	 * rebuilt only when the line appears or disappears, since that changes
	 * its height. Everything else on the card is unchanged by a fill.
	 */
	void updateWatchedOffer(int itemId, @Nullable String offer)
	{
		for (int i = 0; i < watched.size(); i++)
		{
			final WatchedItem item = watched.get(i);
			if (item.itemId != itemId)
			{
				continue;
			}
			if (java.util.Objects.equals(item.offer, offer))
			{
				return;
			}
			watched.set(i, new WatchedItem(item.itemId, item.name, item.image, item.price, item.limit, item.alch,
				offer, item.quote));
			final JLabel line = offerLines.get(itemId);
			if (line != null && offer != null && item.offer != null)
			{
				line.setText(wrap(offer));
				return;
			}
			redrawWatchlist();
			return;
		}
	}

	/** The watchlists could not be read. Shown in the tab itself. */
	void setWatchlistProblem(String why)
	{
		watchlistProblem = why;
		watched = new ArrayList<>();
		redrawWatchlist();
	}

	/** A note about the last edit: added, removed, refused. Null clears it. */
	void setWatchlistNotice(@Nullable String text, Color colour)
	{
		watchlistNotice.setText(text == null ? "" : wrap(text));
		watchlistNotice.setForeground(colour);
		watchlistNotice.setVisible(text != null);
	}

	private void redrawWatchlist()
	{
		watchlistItems.removeAll();
		offerLines.clear();
		if (paused != null)
		{
			watchlistItems.add(small(paused));
		}
		else if (watchlistProblem != null)
		{
			final JLabel problem = small("Couldn't load your watchlists: " + watchlistProblem);
			problem.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			watchlistItems.add(problem);
		}
		else if (watched.isEmpty())
		{
			watchlistItems.add(small(watchlists.getItemCount() == 0
				? "No watchlist yet. Right-click an item in the Grand Exchange and choose \"Add to watchlist\" "
				+ "to start one."
				: "Nothing on this watchlist. Right-click an item in the Grand Exchange to add one."));
		}
		for (WatchedItem item : watched)
		{
			watchlistItems.add(watchedRow(item));
			watchlistItems.add(Box.createVerticalStrut(4));
		}
		watchlistItems.revalidate();
		watchlistItems.repaint();
	}

	/**
	 * One watched item, as a card: the sprite beside the name, then the site's
	 * prices, margin and ROI on their own wrapped lines, then the buttons.
	 *
	 * <p>Stacked rather than side by side because the sidebar is narrow;
	 * nothing here is truncated, the card grows to fit.
	 */
	private JPanel watchedRow(WatchedItem item)
	{
		final JPanel card = card();
		final String name = item.name == null || item.name.isEmpty() ? "Item " + item.itemId : item.name;

		final JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setToolTipText(name);
		if (item.image != null)
		{
			item.image.addTo(icon);
		}
		head.add(icon, BorderLayout.WEST);
		final JLabel title = new JLabel(wrap(name));
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		head.add(title, BorderLayout.CENTER);
		card.add(head);
		card.add(Box.createVerticalStrut(4));

		if (item.quote != null)
		{
			final JLabel prices = small(pricesLine(item.quote));
			prices.setToolTipText("The price you can buy at and the price you can sell at right now, from flippingrs.com.");
			card.add(prices);
			final JLabel margin = small(marginLine(item.quote));
			margin.setForeground(item.quote.getNetMargin() < 0
				? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR);
			margin.setToolTipText("Profit per item after tax, and the return on what you'd pay.");
			card.add(margin);
			final String limits = limitLine(item);
			if (!limits.isEmpty())
			{
				card.add(small(limits));
			}
		}
		else
		{
			final JLabel facts = small(facts(item));
			facts.setToolTipText("RuneLite's price, the buy limit and the alch value. flippingrs.com has no prices for this item yet.");
			card.add(facts);
		}
		if (item.offer != null)
		{
			final JLabel offer = small(item.offer);
			offer.setForeground(ColorScheme.BRAND_ORANGE);
			card.add(offer);
			offerLines.put(item.itemId, offer);
		}
		card.add(Box.createVerticalStrut(5));

		final JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		final JButton open = new JButton("Open");
		open.setToolTipText("Open " + name + " in your browser.");
		open.addActionListener(e -> onOpenItem.accept(item.itemId));
		final JButton remove = new JButton("Remove");
		remove.setToolTipText("Remove from the watchlist.");
		remove.addActionListener(e -> onRemoveItem.accept(item.itemId));
		for (JButton button : new JButton[]{open, remove})
		{
			button.setFont(FontManager.getRunescapeSmallFont());
			button.setMargin(new Insets(1, 4, 1, 4));
			buttons.add(button);
		}
		card.add(buttons);

		// As tall as its content and no taller, so a short card does not get
		// stretched to share space with a long one.
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	/** "Buy 1.48M · Sell 1.52M" */
	static String pricesLine(FlippingRsApi.Quote q)
	{
		return "Buy " + gp(q.getBuyAt()) + " · Sell " + gp(q.getSellAt());
	}

	/** "Margin +30.0K · ROI 2.0%" */
	static String marginLine(FlippingRsApi.Quote q)
	{
		return "Margin " + signed(q.getNetMargin()) + " · ROI " + pct(q.getRoi());
	}

	/** "Limit 70 · +2.10M per limit · 1.2K traded/24h", leaving out what is unknown. */
	static String limitLine(WatchedItem item)
	{
		final FlippingRsApi.Quote q = item.quote;
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

	private static JPanel card()
	{
		final JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
		return card;
	}

	private static JLabel small(String text)
	{
		final JLabel label = new JLabel(wrap(text));
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** Wraps text as HTML so a label can break lines, escaping it first. */
	private static String wrap(String text)
	{
		return "<html><body style='width:150px'>" + escape(text) + "</body></html>";
	}

	// ---------------------------------------------------------- test seams
	//
	// Package-private and used only by the tests. The alternative is asserting
	// against the Swing component tree, which breaks whenever the layout is
	// touched and tests the wrong thing.

	List<String> recentForTest()
	{
		return new ArrayList<>(recent);
	}

	List<String> pendingForTest()
	{
		return new ArrayList<>(pending);
	}

	/** The watched item ids as rendered, in order. */
	List<Integer> watchlistForTest()
	{
		final List<Integer> ids = new ArrayList<>();
		for (WatchedItem item : watched)
		{
			ids.add(item.itemId);
		}
		return ids;
	}

	/** The live-offer line of a watched item's card, or null if it has none. */
	@Nullable
	String watchlistOfferForTest(int itemId)
	{
		for (WatchedItem item : watched)
		{
			if (item.itemId == itemId)
			{
				return item.offer;
			}
		}
		return null;
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
		for (WatchedItem item : watched)
		{
			if (item.itemId == itemId)
			{
				return item.quote == null ? null : pricesLine(item.quote);
			}
		}
		return null;
	}

	/** The open positions' item ids as rendered, in order. */
	List<Integer> positionsForTest()
	{
		final List<Integer> ids = new ArrayList<>();
		for (FlippingRsApi.Position p : positions)
		{
			ids.add(p.getItemId());
		}
		return ids;
	}

	String journalSummaryForTest()
	{
		return journalSummary.getText();
	}

	@Nullable
	String journalProblemForTest()
	{
		return journalProblem;
	}

	String statusTextForTest()
	{
		return status.getText();
	}

	String subscriptionTextForTest()
	{
		return subscription.getText();
	}

	String activityNoticeForTest()
	{
		return activityNotice.getText();
	}

	String watchlistNoticeForTest()
	{
		return watchlistNotice.getText();
	}

	@Nullable
	String watchlistProblemForTest()
	{
		return watchlistProblem;
	}

	@Nullable
	String activityProblemForTest()
	{
		return recentProblem;
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
		for (int i = 0; i < accounts.getItemCount(); i++)
		{
			if (accounts.getItemAt(i).id.equals(id))
			{
				accounts.setSelectedIndex(i);
				return;
			}
		}
		throw new IllegalArgumentException("no such account in the list: " + id);
	}

	private static Instant occurredAt(GeTransaction tx)
	{
		try
		{
			return Instant.parse(tx.occurredAt);
		}
		catch (RuntimeException e)
		{
			// Missing or unparseable: the line is still worth showing.
			return Instant.now();
		}
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

	/** "5h", "2d 3h", "40m". */
	static String hours(double hours)
	{
		if (hours < 1)
		{
			return Math.round(hours * 60) + "m";
		}
		if (hours < 48)
		{
			return Math.round(hours) + "h";
		}
		final long days = (long) (hours / 24);
		return days + "d " + Math.round(hours - days * 24) + "h";
	}

	/**
	 * The labels render HTML so they can wrap, which means server messages and
	 * item names have to be escaped rather than interpreted.
	 */
	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
