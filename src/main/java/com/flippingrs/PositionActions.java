package com.flippingrs;

import java.io.IOException;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;

/**
 * The two things the Journal tab can do to an open position: record a sale
 * against it, and delete a lot that was never a flip. Both are the same
 * actions the site's own Positions page has, and the server does the maths
 * and answers in its own words when it refuses, which is what the tab shows.
 *
 * <p>Net thread. Neither throws: the user pressed a button and is owed an
 * answer, and an escape here would be a one-shot task dying with the reason
 * in the log and nothing on the tab that asked.
 */
@Slf4j
final class PositionActions
{
	private final FlippingRsConfig config;
	private final Supplier<FlippingRsApi> api;
	private final PanelUpdates panel;
	/** Re-reads the Journal tab after a change. Net thread, synchronous. */
	private final Runnable rereadJournal;

	PositionActions(FlippingRsConfig config, Supplier<FlippingRsApi> api, PanelUpdates panel, Runnable rereadJournal)
	{
		this.config = config;
		this.api = api;
		this.panel = panel;
		this.rereadJournal = rereadJournal;
	}

	/** Records a sale against a position. The server caps the quantity at what is left. */
	void close(String positionId, long sellPrice, @Nullable Long sellQty)
	{
		try
		{
			final String key = keyForAnEdit("close a position");
			if (key == null)
			{
				return;
			}
			api.get().closePosition(key, positionId, sellPrice, sellQty);
			panel.onPanel(p -> p.setJournalNotice("Sale recorded.", ColorScheme.PROGRESS_COMPLETE_COLOR));
			rereadJournal.run();
		}
		catch (IOException e)
		{
			log.debug("could not close the position", e);
			final String why = FlippingRsApi.describe(e);
			panel.onPanel(p -> p.setJournalNotice("Couldn't record the sale: " + why, ColorScheme.PROGRESS_ERROR_COLOR));
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure while closing a position", e);
			panel.onPanel(p -> p.setJournalNotice(
				"Something went wrong recording the sale. Details are in the client log.",
				ColorScheme.PROGRESS_ERROR_COLOR));
		}
	}

	/** Deletes a lot that was never a flip. The sidebar has already asked the user. */
	void delete(String positionId)
	{
		try
		{
			final String key = keyForAnEdit("delete a position");
			if (key == null)
			{
				return;
			}
			api.get().deletePosition(key, positionId);
			panel.onPanel(p -> p.setJournalNotice("Position deleted.", ColorScheme.PROGRESS_COMPLETE_COLOR));
			rereadJournal.run();
		}
		catch (IOException e)
		{
			log.debug("could not delete the position", e);
			final String why = FlippingRsApi.describe(e);
			panel.onPanel(p -> p.setJournalNotice("Couldn't delete the position: " + why, ColorScheme.PROGRESS_ERROR_COLOR));
		}
		catch (RuntimeException e)
		{
			log.warn("unexpected failure while deleting a position", e);
			panel.onPanel(p -> p.setJournalNotice(
				"Something went wrong deleting the position. Details are in the client log.",
				ColorScheme.PROGRESS_ERROR_COLOR));
		}
	}

	/** The key for a journal edit, or null with the reason shown on the Journal tab. */
	@Nullable
	private String keyForAnEdit(String what)
	{
		if (!config.enabled())
		{
			panel.onPanel(p -> p.setJournalNotice("Switch \"Record trades\" back on in the plugin settings to " + what + ".",
				ColorScheme.BRAND_ORANGE));
			return null;
		}
		final String key = config.apiKey().trim();
		if (key.isEmpty())
		{
			panel.onPanel(p -> p.setJournalNotice("Add your API key in the plugin settings to " + what + ".",
				ColorScheme.BRAND_ORANGE));
			return null;
		}
		return key;
	}
}
