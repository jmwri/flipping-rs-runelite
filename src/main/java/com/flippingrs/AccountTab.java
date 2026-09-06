package com.flippingrs;

// The panel's shared vocabulary: the layout helpers every tab builds
// rows out of, and the static text of a row, which the tests pin there.
import static com.flippingrs.FlippingRsPanel.*;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import java.awt.Color;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;

/**
 * The Account tab: whether the plugin is connected, the plan the key's
 * owner is on, and which journal this character files under.
 *
 * <p>The only tab with no list on it, and so the only one with nothing to
 * defer: its handful of labels are written straight through.
 */
final class AccountTab extends SidebarTab
{
	private final JLabel status = new JLabel();
	private final JLabel subscription = new JLabel();
	private final JComboBox<GameAccount> accounts = new JComboBox<>();
	private final JButton reconnect = new JButton("Reconnect");
	private final JPanel body;
	private final PanelActions actions;

	/**
	 * Set while the picker's model is being replaced.
	 *
	 * <p>Repopulating a combo box fires a selection event, and letting that
	 * through would look like the user re-picking the journal and write the
	 * setting back on every reconnect.
	 */
	private boolean repopulating;

	AccountTab(PanelActions actions)
	{
		this.actions = actions;
		final JPanel body = column();
		body.add(hint("Your connection to flippingrs.com, and which journal this character's trades go into."));
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
		accounts.addActionListener(e -> {
			if (!repopulating)
			{
				actions.accountChosen();
			}
		});
		body.add(accounts);
		body.add(Box.createVerticalStrut(10));

		reconnect.addActionListener(e -> actions.reconnect());
		reconnect.setToolTipText("Check your API key again and reload everything from flippingrs.com.");
		reconnect.setAlignmentX(Component.LEFT_ALIGNMENT);
		reconnect.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		body.add(reconnect);
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

	/** The connection status, on the Account tab. */
	void setStatus(String text, Color colour)
	{
		setWrappedText(status, text);
		status.setForeground(colour);
	}

	/** The plan the key's owner is on, in the server's words, or null if not known. */
	void setSubscription(@Nullable String text)
	{
		setWrappedText(subscription, text == null ? "Plan: not checked yet" : text);
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
	 * <p>The selection listener is muted while the model is swapped:
	 * repopulating a combo box fires a selection event, and letting that
	 * through would look like the user re-picking the account and write the
	 * setting back on every reconnect.
	 */
	/**
	 * Read once, when the plugin connects, and again when the key or the
	 * chosen journal changes. Nothing about a plan or a list of journals goes
	 * stale on its own, so nothing re-reads it on a clock.
	 */
	@Override
	String refreshedBy()
	{
		return "on connect";
	}

	@Override
	long refreshEverySeconds()
	{
		return PanelReads.panelRefreshSeconds();
	}

	void setAccounts(List<GameAccount> available, @Nullable String selectedId)
	{
		stamp();
		repopulating = true;
		try
		{
			final DefaultComboBoxModel<GameAccount> model = new DefaultComboBoxModel<>();
			GameAccount remembered = null;
			GameAccount fallback = null;
			for (GameAccount account : available)
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
			repopulating = false;
		}
	}

	@Nullable
	String selectedAccountId()
	{
		final Object selected = accounts.getSelectedItem();
		return selected == null ? null : ((GameAccount) selected).id;
	}

	// ---------------------------------------------------------- test seams

	String statusTextForTest()
	{
		return status.getText();
	}

	String subscriptionTextForTest()
	{
		return subscription.getText();
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
}
