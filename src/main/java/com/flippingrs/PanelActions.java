package com.flippingrs;

import javax.annotation.Nullable;

/**
 * Everything the sidebar can ask the plugin to do.
 *
 * <p>One interface, taken in the panel's constructor, rather than a dozen
 * setters each defaulting to a no-op. The setters could not tell anybody when
 * one was forgotten: a button wired to nothing looks exactly like a button
 * wired to something that decided to do nothing, and neither the panel nor
 * the plugin could notice the difference. Adding a method here breaks the
 * build until the plugin implements it, which is the only moment anyone is
 * actually paying attention.
 *
 * <p>Every method is called on the Swing thread, from a button, a dropdown or
 * RuneLite's own activate and deactivate. None of them may block: the
 * implementations hand the work to the net thread and return.
 */
interface PanelActions
{
	/** "Send now" on the Activity tab. */
	void sendNow();

	/** "Try again" beside the set-aside count on the Activity tab. */
	void retrySetAside();

	/** "Reconnect" on the Account tab. */
	void reconnect();

	/** The journal picker changed. */
	void accountChosen();

	/** The watchlist picker changed. */
	void watchlistChosen();

	/** "View item", or an item's name on the Watchlists tab. */
	void openItem(int itemId);

	/** "Remove" beside a watchlist item. */
	void removeItem(int itemId);

	/** "Find flips" on the Watchlists tab. */
	void findFlips();

	/**
	 * "Close" on an open position, with the sale the user typed.
	 *
	 * @param sellQty how many were sold, or null for everything still held
	 */
	void closePosition(String positionId, long sellPrice, @Nullable Long sellQty);

	/** "Delete" on an open position. The panel has already asked the user. */
	void deletePosition(String positionId);

	/** The sidebar opened on this panel. */
	void shown();

	/** The sidebar closed, or moved off this panel. */
	void hidden();
}
