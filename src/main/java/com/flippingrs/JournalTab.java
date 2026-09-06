package com.flippingrs;

// The panel's shared vocabulary: the layout helpers every tab builds
// rows out of, and the static text of a row, which the tests pin there.
import static com.flippingrs.FlippingRsPanel.*;

import java.awt.Color;
import java.time.Instant;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The Journal tab: your newest fills, as the journal has them.
 *
 * <p>Named for the page it mirrors on flippingrs.com, which is the ledger
 * rather than the analysis. The rows are the server's, not the plugin's: what
 * is drawn here is what was actually recorded, which is the only way a user
 * can tell the difference between a trade that was sent and one that was
 * merely captured.
 */
final class JournalTab extends SidebarTab
{
	private final JPanel recentList = new JPanel();
	private final List<GeTransaction> recent = new ArrayList<>();
	private Map<Integer, AsyncBufferedImage> recentImages = new HashMap<>();
	@Nullable
	private String recentProblem;
	/** Why nothing is being read at all, if that is the case. The panel sets it. */
	@Nullable
	private String paused;
	private final JPanel body;

	JournalTab()
	{
		final JPanel body = column();
		body.add(hint("Your most recent trades, as your flippingrs.com journal recorded them."));
		recentList.setLayout(new BoxLayout(recentList, BoxLayout.Y_AXIS));
		recentList.setAlignmentX(Component.LEFT_ALIGNMENT);
		recentList.setToolTipText("Your most recent trades, as your journal has them.");
		body.add(recentList);
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
	 * No timer. The ledger is re-read when a trade is recorded and when the
	 * sidebar is opened -- both of which are somebody doing something -- so
	 * what is worth saying is what to do, not how long to wait.
	 */
	@Override
	String refreshedBy()
	{
		return "when you trade";
	}

	@Override
	void onPaused(@Nullable String why)
	{
		paused = why;
		if (why != null)
		{
			recent.clear();
			recentProblem = null;
		}
		refresh();
	}

	/**
	 * Replaces the recent trades with what the server recorded, newest first.
	 * Only the first {@link #RECENT_SHOWN} are drawn.
	 */
	void setRecentTrades(List<GeTransaction> newestFirst)
	{
		setRecentTrades(newestFirst, new HashMap<>());
	}

	/** As above, with the items' sprites by item id, resolved by the plugin. */
	void setRecentTrades(List<GeTransaction> newestFirst, Map<Integer, AsyncBufferedImage> images)
	{
		stamp();
		recentProblem = null;
		recent.clear();
		for (GeTransaction tx : newestFirst)
		{
			if (recent.size() >= RECENT_SHOWN)
			{
				break;
			}
			recent.add(tx);
		}
		recentImages = new HashMap<>(images);
		refresh();
	}

	/** The recent trades could not be read. Shown in the tab itself. */
	void setRecentTradesProblem(String why)
	{
		recentProblem = why;
		refresh();
	}

	@Override
	void redraw()
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
			for (GeTransaction tx : recent)
			{
				recentList.add(tradeRow(tx, recentImages.get(tx.itemId)));
				recentList.add(Box.createVerticalStrut(4));
			}
		}
		recentList.revalidate();
		recentList.repaint();
	}

	/**
	 * One recorded trade, as a card: the sprite beside the item's name, then
	 * what happened in exact gp with the per-item price, then when.
	 */
	private JPanel tradeRow(GeTransaction tx, @Nullable AsyncBufferedImage image)
	{
		final JPanel card = card();
		final String name = nameOf(tx);

		final JPanel head = new JPanel(new BorderLayout(ICON_GAP, 0));
		head.setOpaque(false);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_WIDTH, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
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

		final boolean buy = "buy".equals(tx.side);
		final JLabel what = small(whatHappened(tx));
		what.setForeground(buy ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.BRAND_ORANGE);
		card.add(what);
		card.add(small(when(tx, Instant.now())));

		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	// ---------------------------------------------------------- test seams

	/** The recent trades as one line each, the way the buffer list shows them. */
	List<String> recentForTest()
	{
		final List<String> lines = new ArrayList<>(recent.size());
		for (GeTransaction tx : recent)
		{
			lines.add(line(tx));
		}
		return lines;
	}

	@Nullable
	String problemForTest()
	{
		return recentProblem;
	}

	JPanel listForTest()
	{
		return recentList;
	}
}
