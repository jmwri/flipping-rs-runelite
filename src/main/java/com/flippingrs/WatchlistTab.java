package com.flippingrs;

// The panel's shared vocabulary: the layout helpers every tab builds
// rows out of, and the static text of a row, which the tests pin there.
import static com.flippingrs.FlippingRsPanel.*;

import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

/**
 * The Watchlists tab: one of the owner's lists, priced.
 *
 * <p>The same quotes the offer screen draws, so the number in the sidebar
 * and the number over the price box are never two different answers.
 */
final class WatchlistTab extends SidebarTab
{
	private final JComboBox<Watchlist> watchlists = new JComboBox<>();
	private final JLabel watchlistNotice = new JLabel();
	private final Timer watchlistNoticeTimer = new Timer(FlippingRsPanel.NOTICE_SECONDS * 1000,
		e -> setWatchlistNotice(null, ColorScheme.LIGHT_GRAY_COLOR));
	private final JPanel watchlistItems = new JPanel();
	private final JButton findFlips = new JButton("Find flips");
	private List<FlippingRsPanel.WatchedItem> watched = new ArrayList<>();
	/** The live-offer line of each card, so one fill can update one line. */
	private final Map<Integer, JLabel> offerLines = new HashMap<>();
	@Nullable
	private String watchlistProblem;
	/** Why nothing is being read at all, if that is the case. The panel sets it. */
	@Nullable
	private String paused;
	private final JPanel body;
	private final PanelActions actions;

	/** Set while the picker's model is being replaced. See {@link AccountTab}. */
	private boolean repopulating;

	WatchlistTab(PanelActions actions)
	{
		this.actions = actions;
		final JPanel body = column();
		body.add(hint("One of your flippingrs.com watchlists, with live prices. Right-click an item in the "
			+ "Grand Exchange to add it."));
		watchlists.setAlignmentX(Component.LEFT_ALIGNMENT);
		watchlists.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		watchlists.setToolTipText("Which of your flippingrs.com watchlists to show. Right-clicking an item adds it here.");
		watchlists.addActionListener(e -> {
			if (!repopulating)
			{
				actions.watchlistChosen();
			}
		});
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
		findFlips.addActionListener(e -> actions.findFlips());
		findFlips.setToolTipText("Open the flip finder in your browser.");
		findFlips.setAlignmentX(Component.LEFT_ALIGNMENT);
		findFlips.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(findFlips);
		// The freshness line last, under everything it qualifies. Empty until
		// the first read, so it takes no room on a tab that has nothing yet.
		body.add(Box.createVerticalStrut(8));
		body.add(refreshLine());
		this.body = body;
	}

	@Override
	JPanel body()
	{
		return body;
	}

	/**
	 * The only tab whose next read is a scheduled thing, so the only one that
	 * can honestly count down to it.
	 *
	 * <p>The plugin's own constant, not a copy of the number: a cadence that
	 * changed in one place and not the other would leave the tab counting down
	 * to a moment that has already passed, or sitting at "due now" for fifteen
	 * seconds.
	 */
	@Override
	long refreshEverySeconds()
	{
		return FlippingRsPlugin.QUOTE_REFRESH_SECONDS;
	}

	@Override
	void onPaused(@Nullable String why)
	{
		paused = why;
		if (why != null)
		{
			watched = new ArrayList<>();
			watchlistProblem = null;
		}
		refresh();
	}

	/**
	 * Replaces the watchlist picker. The selection is the plugin's to decide,
	 * because it is also the plugin's to remember; an id not in the list
	 * leaves the first entry selected, since any board is a fine place to add
	 * to and the picker says which.
	 */
	void setWatchlists(List<Watchlist> available, @Nullable String selectedId)
	{
		repopulating = true;
		try
		{
			final DefaultComboBoxModel<Watchlist> model = new DefaultComboBoxModel<>();
			Watchlist select = null;
			for (Watchlist watchlist : available)
			{
				// An id is what an edit is addressed to, so a row without one
				// is a row nothing can be added to or removed from.
				if (watchlist == null || watchlist.id == null || watchlist.id.isEmpty())
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
			repopulating = false;
		}
	}

	@Nullable
	String selectedWatchlistId()
	{
		final Object selected = watchlists.getSelectedItem();
		return selected == null ? null : ((Watchlist) selected).id;
	}

	/** Replaces the watchlist's rows, in the server's order. */
	void setWatchlistItems(List<WatchedItem> items)
	{
		stamp();
		watchlistProblem = null;
		watched = new ArrayList<>(items);
		refresh();
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
				setWrappedText(line, offer);
				return;
			}
			refresh();
			return;
		}
	}

	/** The watchlists could not be read. Shown in the tab itself. */
	void setWatchlistProblem(String why)
	{
		watchlistProblem = why;
		watched = new ArrayList<>();
		refresh();
	}

	/**
	 * A note about the last edit: added, removed, refused. Null clears it;
	 * otherwise it clears itself after {@link #NOTICE_SECONDS}.
	 */
	void setWatchlistNotice(@Nullable String text, Color colour)
	{
		setNotice(watchlistNotice, watchlistNoticeTimer, text, colour);
	}

	@Override
	void redraw()
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

		final JPanel head = new JPanel(new BorderLayout(ICON_GAP, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_WIDTH, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setToolTipText(name);
		if (item.image != null)
		{
			item.image.addTo(icon);
		}
		head.add(icon, BorderLayout.WEST);
		final JLabel title = new JLabel();
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		setWrappedTextBesideIcon(title, name);
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
		open.addActionListener(e -> actions.openItem(item.itemId));
		final JButton remove = new JButton("Remove");
		remove.setToolTipText("Remove from the watchlist.");
		remove.addActionListener(e -> actions.removeItem(item.itemId));
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

	// ---------------------------------------------------------- test seams

	/** The watched item ids as rendered, in order. */
	List<Integer> watchedForTest()
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
	String offerForTest(int itemId)
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

	/** The site's price line for a watched item, or null if it has no quote. */
	@Nullable
	String pricesForTest(int itemId)
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

	String noticeForTest()
	{
		return watchlistNotice.getText();
	}

	boolean noticeShowingForTest()
	{
		return watchlistNotice.isVisible() && !watchlistNotice.getText().isEmpty();
	}

	@Nullable
	String problemForTest()
	{
		return watchlistProblem;
	}

	Timer noticeTimerForTest()
	{
		return watchlistNoticeTimer;
	}

	JPanel listForTest()
	{
		return watchlistItems;
	}

	/** Selects by id the way a user clicking the combo box would. */
	void setSelectedForTest(String id)
	{
		for (int i = 0; i < watchlists.getItemCount(); i++)
		{
			if (id.equals(watchlists.getItemAt(i).id))
			{
				watchlists.setSelectedIndex(i);
				return;
			}
		}
		throw new IllegalArgumentException("no such watchlist in the list: " + id);
	}
}
