package com.flippingrs;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Locale;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel: what the plugin is connected to, where trades are being
 * filed, and proof that it is working.
 *
 * <p>That last part is the point. Automatic capture is invisible by nature, and
 * a flipper cannot tell "recording silently" from "quietly broken since
 * Tuesday" without being shown the trades as they are captured. So the panel
 * always answers three questions: is the key good, which journal is this going
 * to, and what was recorded most recently.
 *
 * <p>Every method here must be called on the Swing thread. The plugin marshals.
 */
public class FlippingRsPanel extends PluginPanel
{
	private static final DateTimeFormatter TIME = DateTimeFormatter
		.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private static final int RECENT_SHOWN = 8;

	private final JLabel status = new JLabel();
	private final JComboBox<FlippingRsApi.GameAccount> accounts = new JComboBox<>();
	private final JLabel queued = new JLabel();
	private final JLabel recorded = new JLabel();
	private final JLabel lastSync = new JLabel();
	private final JButton syncNow = new JButton("Send now");
	private final JButton reconnect = new JButton("Reconnect");
	private final JPanel recentList = new JPanel();
	private final Deque<String> recent = new ArrayDeque<>();

	/** Set by the plugin; fires when the user picks a different game account. */
	private Runnable onAccountChosen = () -> {
	};

	public FlippingRsPanel()
	{
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		body.add(header("FlippingRS"));
		body.add(Box.createVerticalStrut(4));

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(status);
		body.add(Box.createVerticalStrut(10));

		body.add(header("Journal"));
		body.add(Box.createVerticalStrut(4));
		accounts.setAlignmentX(Component.LEFT_ALIGNMENT);
		accounts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		accounts.setToolTipText("Which FlippingRS game account this RuneScape account's trades are filed under. "
			+ "Remembered per RuneScape account, so an alt can have its own journal.");
		accounts.addActionListener(e -> onAccountChosen.run());
		body.add(accounts);
		body.add(Box.createVerticalStrut(10));

		body.add(header("Activity"));
		body.add(Box.createVerticalStrut(4));
		final JPanel stats = new JPanel(new GridLayout(0, 1, 0, 2));
		stats.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		for (JLabel label : new JLabel[]{recorded, queued, lastSync})
		{
			label.setFont(FontManager.getRunescapeSmallFont());
			stats.add(label);
		}
		stats.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(stats);
		body.add(Box.createVerticalStrut(8));

		final JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		syncNow.setToolTipText("Send anything waiting right now, instead of at the next interval.");
		reconnect.setToolTipText("Re-check the API key and reload the list of game accounts.");
		buttons.add(syncNow);
		buttons.add(reconnect);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(buttons);
		body.add(Box.createVerticalStrut(12));

		body.add(header("Recent trades"));
		body.add(Box.createVerticalStrut(4));
		recentList.setLayout(new BoxLayout(recentList, BoxLayout.Y_AXIS));
		recentList.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(recentList);

		add(body, BorderLayout.NORTH);

		setStatus("Not connected", ColorScheme.LIGHT_GRAY_COLOR);
		setCounts(0, 0);
		setLastSync(null, null);
		redrawRecent();
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

	private static JLabel header(String text)
	{
		final JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	void setStatus(String text, Color colour)
	{
		status.setText("<html><body style='width:150px'>" + escape(text) + "</body></html>");
		status.setForeground(colour);
	}

	/**
	 * Replaces the account list, restoring the current selection if it survives.
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
			FlippingRsApi.GameAccount select = null;
			for (FlippingRsApi.GameAccount account : available)
			{
				if (account == null || account.id == null)
				{
					continue;
				}
				model.addElement(account);
				if (account.id.equals(selectedId))
				{
					select = account;
				}
				if (select == null && account.isDefault)
				{
					select = account;
				}
			}
			accounts.setModel(model);
			if (select != null)
			{
				accounts.setSelectedItem(select);
			}
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
			lastSync.setText("<html><body style='width:150px'>Last attempt failed: "
				+ escape(problem) + "</body></html>");
			lastSync.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			return;
		}
		lastSync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lastSync.setText(at == null ? "Last sent: never" : "Last sent: " + TIME.format(at));
	}

	/** Adds a line to the recent list, newest first. */
	void addRecent(GeTransaction tx)
	{
		final String line = ("buy".equals(tx.side) ? "Bought " : "Sold ")
			+ tx.quantity + " x " + tx.itemName
			+ " for " + gp(tx.grossValue) + (tx.estimated ? " (approx)" : "");
		// The fill's own timestamp, not the clock. A queue that drained after a
		// spell offline would otherwise stamp every recovered trade with the
		// moment the panel happened to redraw.
		recent.addFirst(TIME.format(occurredAt(tx)) + "  " + line);
		while (recent.size() > RECENT_SHOWN)
		{
			recent.removeLast();
		}
		redrawRecent();
	}

	private void redrawRecent()
	{
		recentList.removeAll();
		if (recent.isEmpty())
		{
			final JLabel empty = new JLabel("Nothing captured yet.");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			recentList.add(empty);
		}
		else
		{
			for (String line : recent)
			{
				final JLabel label = new JLabel("<html><body style='width:150px'>" + escape(line) + "</body></html>");
				label.setFont(FontManager.getRunescapeSmallFont());
				label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				label.setAlignmentX(Component.LEFT_ALIGNMENT);
				recentList.add(label);
			}
		}
		recentList.revalidate();
		recentList.repaint();
	}

	// ---------------------------------------------------------- test seams
	//
	// Package-private and used only by FlippingRsPanelTest. The alternative is
	// asserting against the Swing component tree, which breaks whenever the
	// layout is touched and tests the wrong thing.

	List<String> recentForTest()
	{
		return new ArrayList<>(recent);
	}

	String statusTextForTest()
	{
		return status.getText();
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

	/**
	 * The labels render HTML so they can wrap, which means server messages and
	 * item names have to be escaped rather than interpreted.
	 */
	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
