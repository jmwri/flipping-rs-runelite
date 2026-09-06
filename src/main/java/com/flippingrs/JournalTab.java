package com.flippingrs;

// The panel's shared vocabulary: the layout helpers every tab builds
// rows out of, and the static text of a row, which the tests pin there.
import static com.flippingrs.FlippingRsPanel.*;

import java.awt.Insets;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import java.awt.Color;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.Timer;

/**
 * The Journal tab: the week's verdict and everything still held.
 *
 * <p>Every figure on it is the server's. The two buttons on a position are
 * the only writes the sidebar makes that are not a report of something the
 * plugin watched happen, and the destructive one asks first.
 */
final class JournalTab extends SidebarTab
{
	private final JLabel journalSummary = new JLabel();
	private final JLabel journalOpen = new JLabel();
	private final JLabel journalNotice = new JLabel();
	private final Timer journalNoticeTimer = new Timer(FlippingRsPanel.NOTICE_SECONDS * 1000,
		e -> setJournalNotice(null, ColorScheme.LIGHT_GRAY_COLOR));
	private final JPanel positionList = new JPanel();
	private List<Position> positions = new ArrayList<>();
	private boolean journalLoaded;
	@Nullable
	private String journalProblem;
	/**
	 * The positions currently drawn as cards, as {@link #signatureOf} sees
	 * them. Null until the first draw; only set once the cards are up.
	 */
	@Nullable
	private String drawnPositions;
	/** Why nothing is being read at all, if that is the case. The panel sets it. */
	@Nullable
	private String paused;
	private final JPanel body;
	private final PanelActions actions;

	JournalTab(PanelActions actions)
	{
		this.actions = actions;
		final JPanel body = column();
		body.add(hint("Your last seven days and what you are holding, worked out by flippingrs.com."));
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
		journalNotice.setFont(FontManager.getRunescapeSmallFont());
		journalNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(journalNotice);
		body.add(Box.createVerticalStrut(4));
		positionList.setLayout(new BoxLayout(positionList, BoxLayout.Y_AXIS));
		positionList.setAlignmentX(Component.LEFT_ALIGNMENT);
		positionList.setToolTipText("What you're holding, what it cost you, and what it's worth right now.");
		body.add(positionList);
		this.body = body;
		drawSummary();
	}

	@Override
	JPanel body()
	{
		return body;
	}

	@Override
	void paused(@Nullable String why)
	{
		paused = why;
		if (why != null)
		{
			positions = new ArrayList<>();
			journalLoaded = false;
			journalProblem = null;
		}
		drawSummary();
		refresh();
	}

	/** The journal's week and its open positions, as the server has them. */
	void setJournal(Analytics week, Positions open)
	{
		journalProblem = null;
		journalLoaded = true;
		setWrappedText(journalSummary, summarise(week));
		journalSummary.setForeground(week.getRealisedProfit() < 0
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		final Positions.Summary totals = open.getSummary();
		positions = open.getPositions();
		setWrappedText(journalOpen, positions.isEmpty()
			? "No open positions."
			: totals.openPositions + " open · cost " + gp(totals.costBasis) + " · value " + gp(totals.marketValue)
			+ " · P&L " + signed(totals.unrealisedPnl)
			+ (totals.marketDataAvailable ? "" : " (no market data)"));
		refresh();
	}

	/**
	 * A note about the last close or delete: done, or refused in the
	 * server's words. Null clears it; otherwise it clears itself after
	 * {@link #NOTICE_SECONDS}.
	 */
	void setJournalNotice(@Nullable String text, Color colour)
	{
		setNotice(journalNotice, journalNoticeTimer, text, colour);
	}

	/** The journal could not be read. Shown in the tab itself. */
	void setJournalProblem(String why)
	{
		journalProblem = why;
		journalLoaded = false;
		positions = new ArrayList<>();
		drawSummary();
		refresh();
	}

	/**
	 * The two summary lines, for the states that are not a loaded journal:
	 * nothing being read, a read that failed, or nothing read yet.
	 *
	 * <p>Two labels, and they cost nothing next to the cards, so unlike the
	 * cards they are kept current whether or not this tab is showing. A user
	 * who opens the sidebar on another tab and then comes to this one should
	 * not be told the journal is "not loaded yet" when it plainly is.
	 * A journal that did load sets these lines itself, in {@link #setJournal}.
	 */
	private void drawSummary()
	{
		if (paused != null)
		{
			setWrappedText(journalSummary, paused);
			journalSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			journalOpen.setText("");
		}
		else if (journalProblem != null)
		{
			setWrappedText(journalSummary, "Couldn't load your journal: " + journalProblem);
			journalSummary.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			journalOpen.setText("");
		}
		else if (!journalLoaded)
		{
			setWrappedText(journalSummary, "Not loaded yet.");
			journalSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			journalOpen.setText("");
		}
	}

	@Override
	void redraw()
	{
		// Every card is torn down and built again, and a position card is the
		// dearest kind: a title, four lines and two buttons. Two hundred open
		// lots measured at 154ms of the Swing thread, which is the client's,
		// and the journal is redrawn on every read and every time this tab is
		// picked -- mostly with the same lots at the same prices. So what is
		// already on screen is left alone when a redraw would not change it.
		final String signature = signatureOf(positions);
		if (signature.equals(drawnPositions))
		{
			return;
		}
		drawnPositions = signature;

		positionList.removeAll();
		for (Position position : positions)
		{
			positionList.add(positionRow(position));
			positionList.add(Box.createVerticalStrut(4));
		}
		positionList.revalidate();
		positionList.repaint();
	}

	/** One open position: what is held, what it cost, what it is worth now. */
	private JPanel positionRow(Position p)
	{
		final JPanel card = card();
		final JLabel title = new JLabel();
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		setWrappedText(title, p.getItemName().isEmpty() ? "Item " + p.getItemId() : p.getItemName());
		card.add(title);
		card.add(small(p.getRemainingQty() + " left · held " + hours(p.getHoursHeld())));
		card.add(small(positionPrices(p)));
		final JLabel now = small("P&L " + signedExact(p.getUnrealisedPnl()) + " (" + pct(p.getUnrealisedRoi()) + ")"
			+ (p.getBreakEvenSell() > 0 ? " · break even " + exact(p.getBreakEvenSell()) : ""));
		now.setForeground(p.getUnrealisedPnl() < 0 ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR);
		card.add(now);
		if (p.isStale())
		{
			final JLabel stale = small("Stale: you've held this much longer than this item usually takes to flip.");
			stale.setForeground(ColorScheme.BRAND_ORANGE);
			card.add(stale);
		}
		if (!p.getId().isEmpty())
		{
			card.add(Box.createVerticalStrut(5));
			final JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
			buttons.setOpaque(false);
			buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
			buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
			final JButton close = new JButton("Close");
			close.setToolTipText("Record a sale of this position at a price you enter.");
			close.addActionListener(e -> promptClose(p));
			final JButton delete = new JButton("Delete");
			delete.setToolTipText("Not a flip? Delete this record so later sales of the item are not counted against it.");
			delete.addActionListener(e -> promptDelete(p));
			for (JButton button : new JButton[]{close, delete})
			{
				button.setFont(FontManager.getRunescapeSmallFont());
				button.setMargin(new Insets(1, 4, 1, 4));
				buttons.add(button);
			}
			card.add(buttons);
		}
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
	}

	/**
	 * What Close does with what was typed into it.
	 *
	 * <p>Apart from the box itself, which cannot be opened without someone to
	 * close it. A sale recorded from the wrong field, or for none of the
	 * position instead of all of it, is a wrong journal entry that the player
	 * asked for by hand and would have no reason to doubt.
	 */
	void closeAsTyped(Position p, String priceText, String quantityText)
	{
		final long sellPrice = parseGp(priceText);
		final long sellQty = parseGp(quantityText);
		if (sellPrice <= 0)
		{
			setJournalNotice("A sale price is needed to close a position.", ColorScheme.BRAND_ORANGE);
			return;
		}
		// Nothing readable in the count means all of what is left, which is
		// what the box offers and what the site takes a missing count as.
		closePosition(p.getId(), sellPrice, sellQty > 0 ? sellQty : null);
	}

	/**
	 * Asks for the sale price and quantity, prefilled with the price a sale
	 * lists at and everything still held, then hands the answer to the
	 * plugin. Nothing is sent unless the user confirms.
	 */
	private void promptClose(Position p)
	{
		final long suggested = suggestedSalePrice(p);
		final JTextField price = new JTextField(suggested > 0 ? exact(suggested) : "");
		final JTextField quantity = new JTextField(Long.toString(p.getRemainingQty()));
		final JPanel form = new JPanel(new GridLayout(0, 1, 0, 2));
		form.add(new JLabel("Sale price per item"));
		form.add(price);
		form.add(new JLabel("How many sold (" + p.getRemainingQty() + " held)"));
		form.add(quantity);
		final int answer = JOptionPane.showConfirmDialog(body, form,
			"Close " + (p.getItemName().isEmpty() ? "position" : p.getItemName()),
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (answer != JOptionPane.OK_OPTION)
		{
			return;
		}
		closeAsTyped(p, price.getText(), quantity.getText());
	}

	private void promptDelete(Position p)
	{
		final int answer = JOptionPane.showConfirmDialog(body,
			"Delete this record? Later sales of " + (p.getItemName().isEmpty() ? "this item" : p.getItemName())
				+ " will not be counted against it. Your recorded trades are kept.",
			"Delete position", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (answer == JOptionPane.OK_OPTION)
		{
			actions.deletePosition(p.getId());
		}
	}

	/** The way a user would close a position, without the dialog. */
	void closePosition(String positionId, long sellPrice, @Nullable Long sellQty)
	{
		actions.closePosition(positionId, sellPrice, sellQty);
	}

	// ---------------------------------------------------------- test seams

	/** The open positions' item ids as rendered, in order. */
	List<Integer> positionsForTest()
	{
		final List<Integer> ids = new ArrayList<>();
		for (Position p : positions)
		{
			ids.add(p.getItemId());
		}
		return ids;
	}

	String summaryForTest()
	{
		return journalSummary.getText();
	}

	String noticeForTest()
	{
		return journalNotice.getText();
	}

	@Nullable
	String problemForTest()
	{
		return journalProblem;
	}

	Timer noticeTimerForTest()
	{
		return journalNoticeTimer;
	}

	JPanel listForTest()
	{
		return positionList;
	}
}
