package com.flippingrs;

/**
 * One incremental fill on one Grand Exchange slot, as sent to FlippingRS.
 *
 * <p>This is deliberately a report of what the exchange did, not a flip. The
 * plugin never decides which purchase a sale belongs to, what a flip earned, or
 * what tax was due; the server does all of that from these rows. Keeping the
 * judgement on the server means matching can be fixed and replayed over the
 * history, rather than being frozen inside whichever plugin version a user
 * happens to have installed.
 *
 * <p>Field names are the JSON wire format. Gson serialises this directly.
 */
public class GeTransaction
{
	/**
	 * Idempotency key, minted here before the fill is first sent and never
	 * changed. The server drops a repeat, which is what makes the retry queue
	 * safe: a request whose response went missing can be sent again without
	 * doubling the user's recorded profit.
	 */
	String id;

	String offerRef;

	int itemId;
	String itemName;
	/** "buy" or "sell". */
	String side;

	long quantity;
	/**
	 * The exact gp that moved for {@link #quantity} items, from the client's
	 * running total rather than price times quantity. A buy fills at or under
	 * the listed price and a part-filled offer mixes prices, so the listed price
	 * is what was asked for and this is what happened.
	 */
	long grossValue;
	long offerPrice;
	long offerTotal;

	/** The offer reached a terminal state with this fill; no more is coming. */
	boolean completed;
	boolean cancelled;
	/**
	 * grossValue had to be reconstructed as price times quantity because the
	 * client's running total was unusable. Sent so a suspect figure can be found
	 * later rather than quietly blending into someone's profit.
	 */
	boolean estimated;

	int slot;
	int world;

	/**
	 * ISO-8601 UTC, e.g. 2026-08-31T16:10:12.482Z. Null when the fill was not
	 * watched happening -- see {@link #source} -- because a time nobody
	 * observed is not something to make up.
	 */
	String occurredAt;

	/** A fill the plugin watched happen, and timed. */
	static final String SOURCE_LIVE = "live";
	/**
	 * A fill that had already happened when the plugin first saw the offer:
	 * the progress it found on an offer it has no baseline for. Real, but
	 * untimed, and possibly already in the journal from elsewhere, which is
	 * the server's to decide.
	 */
	static final String SOURCE_ADOPTED = "adopted";

	/** One of the SOURCE_ constants. The server treats absent as live. */
	String source = SOURCE_LIVE;

	@Override
	public String toString()
	{
		return side + " " + quantity + " x " + itemName + " for " + grossValue + "gp";
	}
}
