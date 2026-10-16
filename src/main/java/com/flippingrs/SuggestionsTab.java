package com.flippingrs;

// The panel's shared vocabulary: the layout helpers every tab builds
// rows out of, and the static text of a row, which the tests pin there.
import static com.flippingrs.FlippingRsPanel.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The Suggestions tab: items flippingrs.com thinks are worth flipping now.
 *
 * <p>It only shows them. Nothing here touches the game: the one thing a row
 * can do is copy the item's name to the clipboard. The numbers are the
 * server's, and are formatted with the same helpers the Watchlists tab uses.
 */
final class SuggestionsTab extends SidebarTab
{
	/** The most rows drawn, whatever the server sends. */
	static final int MAX_ROWS = 15;

	static final String EMPTY = "Nothing fits this budget right now";
	static final String LOADING = "Loading suggestions";
	static final String RATE_LIMITED = "Rate limited, trying again in a minute.";
	static final String KEY_REFUSED = "flippingrs.com did not accept your plugin key.";
	static final String KEY_HINT = "Check your plugin key on the Account tab";
	static final String FOOTNOTE = "Prices are from the Grand Exchange feed and can move. You decide what to trade.";

	private final PanelActions actions;
	private final Runnable openAccount;
	private final JPanel list = new JPanel();
	private final JPanel body;

	/** Where "Copy name" puts the name. A seam, because a test has no clipboard. */
	private Consumer<String> clipboard = SuggestionsTab::toClipboard;

	private List<Suggestion> rows = new ArrayList<>();
	private Map<Integer, AsyncBufferedImage> images = new HashMap<>();
	private long budget;
	/** Whether a reply has ever arrived, so "loading" is not shown over an empty answer. */
	private boolean loaded;
	/** A line that replaces everything else: no key, or recording off. */
	@Nullable
	private String notice;
	/** A line above the rows saying the last read did not work. */
	@Nullable
	private String problem;
	/** Whether the problem is the key, in which case the Account tab is offered. */
	private boolean keyProblem;
	/** What is drawn, so a reply that changes nothing redraws nothing. */
	private String drawn = "";
	private boolean visible;

	SuggestionsTab(PanelActions actions, Runnable openAccount)
	{
		this.actions = actions;
		this.openAccount = openAccount;
		final JPanel body = column();
		body.add(hint("Ideas for what to flip, worked out on flippingrs.com. Set a budget in the plugin settings."));
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(list);
		body.add(Box.createVerticalStrut(8));
		final JButton refresh = new JButton("Refresh");
		refresh.setToolTipText("Read the suggestions again.");
		refresh.setAlignmentX(Component.LEFT_ALIGNMENT);
		refresh.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		refresh.addActionListener(e -> actions.refreshSuggestions());
		body.add(refresh);
		body.add(Box.createVerticalStrut(8));
		body.add(small(FOOTNOTE));
		body.add(Box.createVerticalStrut(8));
		body.add(refreshLine());
		this.body = body;
		redraw();
	}

	@Override
	JPanel body()
	{
		return body;
	}

	@Override
	long refreshEverySeconds()
	{
		return SuggestionReads.REFRESH_SECONDS;
	}

	@Override
	void onShowingChanged(boolean nowShowing)
	{
		if (nowShowing != visible)
		{
			visible = nowShowing;
			actions.suggestionsShowing(nowShowing);
		}
	}

	// -------------------------------------------------------------- the data

	/** A reply: the rows, in the server's order, and the budget it worked to. */
	void setSuggestions(List<Suggestion> items, Map<Integer, AsyncBufferedImage> sprites, long forBudget)
	{
		stamp();
		rows = new ArrayList<>(items.size() > MAX_ROWS ? items.subList(0, MAX_ROWS) : items);
		images = new HashMap<>(sprites);
		budget = forBudget;
		loaded = true;
		notice = null;
		problem = null;
		keyProblem = false;
		changed();
	}

	/**
	 * The last read failed. The rows already on screen stay, unless the key was
	 * refused: those were read with a key that no longer works.
	 */
	void setProblem(String why, boolean keyRefused)
	{
		notice = null;
		problem = why;
		keyProblem = keyRefused;
		if (keyRefused)
		{
			rows = new ArrayList<>();
			loaded = false;
		}
		changed();
	}

	/** Nothing is being read, and why. */
	void setNotice(@Nullable String why)
	{
		notice = why;
		if (why != null)
		{
			problem = null;
			keyProblem = false;
			rows = new ArrayList<>();
			loaded = false;
		}
		changed();
	}

	private void changed()
	{
		final String now = signature();
		if (now.equals(drawn))
		{
			return;
		}
		drawn = now;
		refresh();
	}

	private String signature()
	{
		final StringBuilder out = new StringBuilder();
		out.append(notice).append(SEP).append(problem).append(SEP).append(keyProblem).append(SEP)
			.append(loaded).append(SEP).append(budget).append(ROW);
		for (Suggestion s : rows)
		{
			out.append(s.id).append(SEP).append(s.name).append(SEP).append(s.buy).append(SEP).append(s.sell)
				.append(SEP).append(s.margin).append(SEP).append(s.roi).append(SEP).append(s.buyLimit)
				.append(SEP).append(s.volume24h).append(SEP).append(s.confidence).append(SEP)
				.append(s.profitPerLimit).append(SEP).append(images.get(s.id) != null).append(ROW);
		}
		return out.toString();
	}

	@Override
	void redraw()
	{
		list.removeAll();
		if (notice != null)
		{
			list.add(small(notice));
		}
		else
		{
			if (problem != null)
			{
				final JLabel line = small(problem);
				line.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
				list.add(line);
				if (keyProblem)
				{
					list.add(Box.createVerticalStrut(2));
					list.add(keyLink());
				}
				list.add(Box.createVerticalStrut(6));
			}
			if (!loaded)
			{
				if (problem == null)
				{
					list.add(small(LOADING));
				}
			}
			else if (rows.isEmpty())
			{
				list.add(small(EMPTY));
			}
			else
			{
				if (budget > 0)
				{
					list.add(small("Budget " + gp(budget)));
					list.add(Box.createVerticalStrut(4));
				}
				for (Suggestion row : rows)
				{
					list.add(row(row));
					list.add(Box.createVerticalStrut(4));
				}
			}
		}
		list.revalidate();
		list.repaint();
	}

	/** A button that reads as a link, so it can be reached from the keyboard too. */
	private JButton keyLink()
	{
		final JButton link = new JButton(KEY_HINT);
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setForeground(ColorScheme.BRAND_ORANGE);
		link.setBorderPainted(false);
		link.setContentAreaFilled(false);
		link.setFocusPainted(false);
		link.setMargin(new Insets(0, 0, 0, 0));
		link.setHorizontalAlignment(SwingConstants.LEFT);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setAlignmentX(Component.LEFT_ALIGNMENT);
		link.addActionListener(e -> openAccount.run());
		return link;
	}

	private JPanel row(Suggestion s)
	{
		final JPanel card = card();
		final String name = nameOf(s);

		final JPanel head = new JPanel(new BorderLayout(ICON_GAP, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_WIDTH, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setToolTipText(name);
		final AsyncBufferedImage image = images.get(s.id);
		if (image != null)
		{
			image.addTo(icon);
		}
		head.add(icon, BorderLayout.WEST);
		final JLabel title = new JLabel();
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		setWrappedTextBesideIcon(title, name);
		head.add(title, BorderLayout.CENTER);
		card.add(head);
		card.add(Box.createVerticalStrut(4));

		final String prices = pricesText(s);
		if (!prices.isEmpty())
		{
			final JLabel line = small(prices);
			line.setToolTipText("The price to place a buy offer at and the price to list at, from flippingrs.com.");
			card.add(line);
		}
		final String margin = marginText(s);
		if (!margin.isEmpty())
		{
			final JLabel line = small(margin);
			line.setForeground(s.margin < 0 ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR);
			line.setToolTipText("Profit per item after tax, and the return on what you'd pay."
				+ (s.confidence > 0 ? " Confidence " + s.confidence + " out of 100." : ""));
			card.add(line);
		}
		final String limits = limitText(s);
		if (!limits.isEmpty())
		{
			card.add(small(limits));
		}
		card.add(Box.createVerticalStrut(5));

		final JButton copy = new JButton("Copy name");
		copy.setToolTipText("Copy " + name + " to the clipboard, to paste into the Grand Exchange search.");
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setMargin(new Insets(1, 4, 1, 4));
		copy.setAlignmentX(Component.LEFT_ALIGNMENT);
		copy.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		copy.addActionListener(e -> clipboard.accept(name));
		card.add(copy);

		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	// ------------------------------------------------------------ the words
	//
	// Static and stateless, so the tests pin them without drawing anything.

	static String nameOf(Suggestion s)
	{
		if (s.name != null && !s.name.trim().isEmpty())
		{
			return s.name;
		}
		return s.id > 0 ? "Item " + s.id : "Unknown item";
	}

	/** The row's figures as the watchlist's own helpers take them. */
	private static Quote quoteOf(Suggestion s)
	{
		final Quote q = new Quote();
		q.id = s.id;
		q.instantSell = s.buy;
		q.instantBuy = s.sell;
		q.netMargin = s.margin;
		q.roi = s.roi;
		q.buyLimit = s.buyLimit;
		q.profitPerLimit = s.profitPerLimit;
		q.volume24h = s.volume24h;
		return q;
	}

	/** "Buy 768,867 · Sell 765,331", or nothing when the server sent neither price. */
	static String pricesText(Suggestion s)
	{
		return s.buy <= 0 && s.sell <= 0 ? "" : pricesLine(quoteOf(s));
	}

	/** "Margin +3,536 · ROI 0.5%", or nothing when the server sent neither. */
	static String marginText(Suggestion s)
	{
		return s.margin == 0 && s.roi == 0 ? "" : marginLine(quoteOf(s));
	}

	/** "Limit 70 · +247.5K per limit · Volume 4.8K/24h", leaving out what is unknown. */
	static String limitText(Suggestion s)
	{
		return limitLine(new WatchedItem(s.id, nameOf(s), null, 0, 0, 0, null, quoteOf(s)));
	}

	private static void toClipboard(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (java.awt.HeadlessException | IllegalStateException e)
		{
			// No clipboard to copy to, or somebody else has it. Nothing to say.
		}
	}

	// ---------------------------------------------------------- test seams

	void clipboardForTest(Consumer<String> to)
	{
		clipboard = to;
	}

	JPanel listForTest()
	{
		return list;
	}

	List<Suggestion> rowsForTest()
	{
		return Collections.unmodifiableList(rows);
	}

	@Nullable
	String problemForTest()
	{
		return problem;
	}
}
