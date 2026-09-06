package com.flippingrs;

// The panel's shared vocabulary: the layout helpers every tab builds
// rows out of, and the static text of a row, which the tests pin there.
import static com.flippingrs.FlippingRsPanel.*;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import java.awt.Color;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.Timer;

/**
 * The Activity tab: what the plugin itself is doing on this computer.
 *
 * <p>The one tab that is not a picture of the server. It counts what has
 * been captured this session, what is still queued, when the last send
 * landed, and anything that went wrong doing either.
 */
final class ActivityTab extends SidebarTab
{
	private final JLabel recorded = new JLabel();
	private final JLabel queued = new JLabel();
	private final JLabel lastSync = new JLabel();
	private final JLabel activityNotice = new JLabel();
	private final Timer activityNoticeTimer = new Timer(FlippingRsPanel.NOTICE_SECONDS * 1000,
		e -> setActivityNotice(null, ColorScheme.LIGHT_GRAY_COLOR));
	private final JButton syncNow = new JButton("Send now");
	private final JLabel setAsideLine = new JLabel();
	private final JButton retrySetAside = new JButton("Try set-aside trades again");
	private final JPanel pendingList = new JPanel();
	private final List<String> pending = new ArrayList<>();
	private final JPanel body;
	private final PanelActions actions;

	ActivityTab(PanelActions actions)
	{
		this.actions = actions;
		final JPanel body = column();
		body.add(hint("What the plugin is doing on this computer: trades it has recorded this session, "
			+ "and any still waiting to be sent to your journal."));
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

		retrySetAside.addActionListener(e -> actions.retrySetAside());
		retrySetAside.setToolTipText("Put the trades flippingrs.com refused back in the queue and send them again. "
			+ "Worth doing after fixing your API key or picking a different journal.");
		retrySetAside.setAlignmentX(Component.LEFT_ALIGNMENT);
		retrySetAside.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		setAsideLine.setFont(FontManager.getRunescapeSmallFont());
		setAsideLine.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		setAsideLine.setAlignmentX(Component.LEFT_ALIGNMENT);
		setSetAside(0);

		syncNow.addActionListener(e -> actions.sendNow());
		syncNow.setToolTipText("Send your waiting trades now instead of at the next scheduled time.");
		syncNow.setAlignmentX(Component.LEFT_ALIGNMENT);
		syncNow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(syncNow);
		body.add(Box.createVerticalStrut(6));
		body.add(setAsideLine);
		body.add(retrySetAside);
		body.add(Box.createVerticalStrut(10));

		body.add(header("Waiting to send"));
		body.add(Box.createVerticalStrut(4));
		pendingList.setLayout(new BoxLayout(pendingList, BoxLayout.Y_AXIS));
		pendingList.setAlignmentX(Component.LEFT_ALIGNMENT);
		pendingList.setToolTipText("Trades recorded here that your journal hasn't confirmed yet.");
		body.add(pendingList);
		this.body = body;
	}

	@Override
	JPanel body()
	{
		return body;
	}

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
			setWrappedText(lastSync, "Last send failed: " + problem);
			lastSync.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}
		lastSync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastSync.setText(at == null ? "Last sent: never" : "Last sent: " + TIME.format(at));
	}

	/**
	 * A note about capturing or sending: an adopted offer, a refused batch.
	 * Null clears it; otherwise it clears itself after {@link #NOTICE_SECONDS}.
	 */
	void setActivityNotice(@Nullable String text, Color colour)
	{
		setNotice(activityNotice, activityNoticeTimer, text, colour);
	}

	/**
	 * How many fills flippingrs.com refused for good and the plugin filed
	 * rather than deleted.
	 *
	 * <p>Hidden entirely at zero, which is every session that has not gone
	 * wrong. A button offering to retry nothing is a button that invites
	 * somebody to wonder what it would have done.
	 */
	void setSetAside(int count)
	{
		final boolean any = count > 0;
		setAsideLine.setVisible(any);
		retrySetAside.setVisible(any);
		if (any)
		{
			setWrappedText(setAsideLine, count + " trade(s) set aside. If you have since fixed your API key or "
				+ "picked a different journal, try them again.");
		}
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
		refresh();
	}

	@Override
	void redraw()
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

	// ---------------------------------------------------------- test seams

	List<String> pendingForTest()
	{
		return new ArrayList<>(pending);
	}

	/** The "Recorded this session" line, which a new session starts over. */
	String recordedTextForTest()
	{
		return recorded.getText();
	}

	String lastSyncTextForTest()
	{
		return lastSync.getText();
	}

	String noticeForTest()
	{
		return activityNotice.getText();
	}

	/** Whether a notice is still up, i.e. it has not been cleared or expired. */
	boolean noticeShowingForTest()
	{
		return activityNotice.isVisible() && !activityNotice.getText().isEmpty();
	}

	Timer noticeTimerForTest()
	{
		return activityNoticeTimer;
	}

	JPanel listForTest()
	{
		return pendingList;
	}

	/** The user pressing "Send now", listener and all. */
	void pressSendNowForTest()
	{
		syncNow.doClick();
	}

	/** The user pressing "Try set-aside trades again", listener and all. */
	void pressRetrySetAsideForTest()
	{
		retrySetAside.doClick();
	}

	boolean retryOfferedForTest()
	{
		return retrySetAside.isVisible();
	}

	String setAsideTextForTest()
	{
		return setAsideLine.getText();
	}
}
