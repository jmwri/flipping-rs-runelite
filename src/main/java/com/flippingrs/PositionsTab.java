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
 * The Positions tab: everything you are still holding.
 *
 * <p>Its own tab because it is its own page on flippingrs.com, and because
 * the week's verdict -- which used to share this one -- answers a different
 * question. Every figure on it is the server's, marked to market.
 *
 * <p>The two buttons on a position are the only writes the sidebar makes that
 * are not a report of something the plugin watched happen, and the
 * destructive one asks first.
 */
final class PositionsTab extends SidebarTab
{
	private final JLabel openSummary = new JLabel();
	private final JLabel notice = new JLabel();
	private final Timer noticeTimer = new Timer(FlippingRsPanel.NOTICE_SECONDS * 1000,
		e -> setPositionNotice(null, ColorScheme.LIGHT_GRAY_COLOR));
	private final JPanel positionList = new JPanel();
	private List<Position> positions = new ArrayList<>();
	/**
	 * The closed section, hidden until a server sends one.
	 *
	 * <p>Hidden rather than empty, and the distinction matters: a
	 * flippingrs.com that has never heard of closed lots leaves the part out
	 * of its reply altogether, and a section saying "No closed positions yet"
	 * would then be telling somebody with a year of finished flips that they
	 * have none. An empty list from a server that does know is a different
	 * thing and does say that.
	 */
	private final JPanel closedSection = new JPanel();
	private final JLabel closedSummary = new JLabel();
	private final JPanel closedList = new JPanel();
	private List<ClosedPosition> closed = new ArrayList<>();
	private boolean loaded;
	@Nullable
	private String problem;
	/**
	 * The positions currently drawn as cards, as {@link #signatureOf} sees
	 * them. Null until the first draw; only set once the cards are up.
	 */
	@Nullable
	private String drawnPositions;
	@Nullable
	private String drawnClosed;
	/** Why nothing is being read at all, if that is the case. The panel sets it. */
	@Nullable
	private String paused;
	private final JPanel body;
	private final PanelActions actions;

	PositionsTab(PanelActions actions)
	{
		this.actions = actions;
		final JPanel body = column();
		body.add(hint("What you are holding, what it cost you, and what it is worth right now."));
		body.add(header("Open positions"));
		body.add(Box.createVerticalStrut(4));
		openSummary.setFont(FontManager.getRunescapeSmallFont());
		openSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		openSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(openSummary);
		body.add(Box.createVerticalStrut(4));
		notice.setFont(FontManager.getRunescapeSmallFont());
		notice.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(notice);
		body.add(Box.createVerticalStrut(4));
		positionList.setLayout(new BoxLayout(positionList, BoxLayout.Y_AXIS));
		positionList.setAlignmentX(Component.LEFT_ALIGNMENT);
		positionList.setToolTipText("What you're holding, what it cost you, and what it's worth right now.");
		body.add(positionList);

		// Under the open ones, because the open book is the thing somebody
		// acts on and the closed one is the thing they learn from.
		closedSection.setLayout(new BoxLayout(closedSection, BoxLayout.Y_AXIS));
		closedSection.setAlignmentX(Component.LEFT_ALIGNMENT);
		closedSection.setOpaque(false);
		closedSection.add(Box.createVerticalStrut(8));
		closedSection.add(header("Closed positions"));
		closedSection.add(Box.createVerticalStrut(4));
		closedSummary.setFont(FontManager.getRunescapeSmallFont());
		closedSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		closedSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
		closedSection.add(closedSummary);
		closedSection.add(Box.createVerticalStrut(4));
		closedList.setLayout(new BoxLayout(closedList, BoxLayout.Y_AXIS));
		closedList.setAlignmentX(Component.LEFT_ALIGNMENT);
		closedList.setToolTipText("Flips you've finished: what they made after tax.");
		closedSection.add(closedList);
		closedSection.setVisible(false);
		body.add(closedSection);
		// The freshness line last, under everything it qualifies. Empty until
		// the first read, so it takes no room on a tab that has nothing yet.
		body.add(Box.createVerticalStrut(8));
		body.add(refreshLine());
		this.body = body;
		drawSummary();
	}

	@Override
	JPanel body()
	{
		return body;
	}

	/** Read with the week, on a trade and on opening the sidebar. */
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
			positions = new ArrayList<>();
			// The cards go, but the section stays as it was found: whether
			// this server has closed lots at all is not something that stops
			// being true because the plugin stopped reading.
			closed = new ArrayList<>();
			loaded = false;
			problem = null;
		}
		drawSummary();
		refresh();
	}

	/** The open lots and their totals, as the server has them. */
	void setPositions(Positions open)
	{
		stamp();
		problem = null;
		loaded = true;
		final Positions.Summary totals = open.getSummary();
		positions = open.getPositions();
		setWrappedText(openSummary, positions.isEmpty()
			? "No open positions."
			: totals.openPositions + " open · cost " + gp(totals.costBasis) + " · value " + gp(totals.marketValue)
			+ " · P&L " + signed(totals.unrealisedPnl)
			+ (totals.marketDataAvailable ? "" : " (no market data)"));
		refresh();
	}

	/**
	 * The finished lots, as the server has them.
	 *
	 * <p>Only ever called with something the server actually sent, so
	 * reaching here is what makes the section exist at all.
	 */
	void setClosedPositions(ClosedPositions finished)
	{
		closed = finished.getPositions();
		setWrappedText(closedSummary, closedSummaryLine(finished));
		closedSection.setVisible(true);
		refresh();
	}

	/**
	 * A note about the last close or delete: done, or refused in the
	 * server's words. Null clears it; otherwise it clears itself after
	 * {@link #NOTICE_SECONDS}.
	 */
	void setPositionNotice(@Nullable String text, Color colour)
	{
		setNotice(notice, noticeTimer, text, colour);
	}

	/** The journal could not be read. Shown in the tab itself. */
	void setProblem(String why)
	{
		problem = why;
		loaded = false;
		positions = new ArrayList<>();
		closed = new ArrayList<>();
		drawSummary();
		refresh();
	}

	/**
	 * The summary line, for the states that are not a loaded journal: nothing
	 * being read, a read that failed, or nothing read yet.
	 *
	 * <p>One label, and it costs nothing next to the cards, so unlike the
	 * cards it is kept current whether or not this tab is showing. Somebody
	 * who opens the sidebar on another tab and then comes to this one should
	 * not be told the journal is "not loaded yet" when it plainly is. A
	 * journal that did load writes the line itself, in {@link #setPositions}.
	 */
	private void drawSummary()
	{
		if (paused != null)
		{
			setWrappedText(openSummary, paused);
			openSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else if (problem != null)
		{
			setWrappedText(openSummary, "Couldn't load your journal: " + problem);
			openSummary.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		}
		else if (!loaded)
		{
			setWrappedText(openSummary, "Not loaded yet.");
			openSummary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
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
		if (!signature.equals(drawnPositions))
		{
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

		// Its own signature, because the two lists move on their own: closing
		// a lot changes both, but an open lot being marked to market changes
		// only the first, and that happens on every read.
		final String closedSignature = closedSignatureOf(closed);
		if (!closedSignature.equals(drawnClosed))
		{
			drawnClosed = closedSignature;
			closedList.removeAll();
			for (ClosedPosition finished : closed)
			{
				closedList.add(closedRow(finished));
				closedList.add(Box.createVerticalStrut(4));
			}
			closedList.revalidate();
			closedList.repaint();
		}
	}

	/**
	 * One finished lot: what it was, what it cost, what it made.
	 *
	 * <p>No buttons. There is nothing left to do to a lot that is sold, and
	 * the site's Journal page is where one gets corrected.
	 */
	private JPanel closedRow(ClosedPosition p)
	{
		final JPanel card = card();
		final JLabel title = new JLabel();
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		setWrappedText(title, p.getItemName().isEmpty() ? "Item " + p.getItemId() : p.getItemName());
		card.add(title);
		card.add(small(closedHeld(p)));
		card.add(small(closedPrices(p)));
		final JLabel made = small(closedResult(p));
		made.setForeground(p.getNetProfit() < 0
			? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.PROGRESS_COMPLETE_COLOR);
		card.add(made);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
		return card;
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
			setPositionNotice("A sale price is needed to close a position.", ColorScheme.BRAND_ORANGE);
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

	String openSummaryForTest()
	{
		return openSummary.getText();
	}

	/** The closed lots' item ids as rendered, in order. */
	List<Integer> closedForTest()
	{
		final List<Integer> ids = new ArrayList<>();
		for (ClosedPosition p : closed)
		{
			ids.add(p.getItemId());
		}
		return ids;
	}

	String closedSummaryForTest()
	{
		return closedSummary.getText();
	}

	boolean closedShownForTest()
	{
		return closedSection.isVisible();
	}

	JPanel closedListForTest()
	{
		return closedList;
	}

	String noticeForTest()
	{
		return notice.getText();
	}

	@Nullable
	String problemForTest()
	{
		return problem;
	}

	Timer noticeTimerForTest()
	{
		return noticeTimer;
	}

	JPanel listForTest()
	{
		return positionList;
	}
}
