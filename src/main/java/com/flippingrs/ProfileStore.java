package com.flippingrs;

import com.google.gson.Gson;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * What the plugin remembers between sessions, in RuneLite's config.
 *
 * <p>Two of the three things here are per RuneScape profile, because they are
 * a character's rather than a person's: the baseline for each exchange slot,
 * which is what stops a login from re-reporting every offer still on the
 * exchange, and which FlippingRS journal this character files under. The
 * third, which watchlist the right-click entry adds to, is a plain plugin
 * setting: a watchlist is a person's, not a character's.
 *
 * <p>The journal choice is stored per profile rather than as a setting so an
 * alt gets its own journal without anyone remembering to change a dropdown
 * before logging in. Getting it wrong mixes two accounts' numbers together,
 * and since buy limits are tracked per game account, the damage is not
 * cosmetic. It is picked in the side panel, not the settings, for the same
 * reason: a single global dropdown would silently file a main's flips under
 * an alt the first time someone forgot.
 */
@Slf4j
final class ProfileStore
{
	/** Config key prefix for the per-slot baseline. */
	static final String OFFER_KEY = "offer";
	/** Config key for the chosen FlippingRS game account, per RuneScape profile. */
	static final String ACCOUNT_KEY = "gameAccountId";
	/** Config key for which watchlist the right-click entry adds to. Only the choice is kept; the list lives on the site. */
	static final String WATCHLIST_KEY = "watchlistId";

	private final ConfigManager configManager;
	private final Gson gson;

	ProfileStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	// ------------------------------------------------------------- baselines

	@Nullable
	SavedOffer loadOffer(int slot)
	{
		final String json = configManager.getRSProfileConfiguration(FlippingRsConfig.GROUP, OFFER_KEY + "." + slot);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, SavedOffer.class);
		}
		catch (RuntimeException e)
		{
			// A baseline we cannot read is the same as not having one: the offer
			// is adopted rather than re-reported, which is the safe direction.
			log.warn("could not read the saved baseline for slot {}", slot, e);
			return null;
		}
	}

	void saveOffer(int slot, SavedOffer offer)
	{
		configManager.setRSProfileConfiguration(FlippingRsConfig.GROUP, OFFER_KEY + "." + slot, gson.toJson(offer));
	}

	void clearOffer(int slot)
	{
		configManager.unsetRSProfileConfiguration(FlippingRsConfig.GROUP, OFFER_KEY + "." + slot);
	}

	// --------------------------------------------------------------- journal

	/** The FlippingRS journal this RuneScape account files under, or null if none has been chosen. */
	@Nullable
	String chosenAccount()
	{
		final String id = configManager.getRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY);
		return id == null || id.isEmpty() ? null : id;
	}

	/** Only valid while a RuneScape profile is active; the caller checks. */
	void rememberChosenAccount(String id)
	{
		configManager.setRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY, id);
	}

	void forgetChosenAccount()
	{
		configManager.unsetRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY);
	}

	// ------------------------------------------------------------- watchlist

	/** The watchlist the picker is set to, or null if none has been picked. */
	@Nullable
	String rememberedWatchlistId()
	{
		final String id = configManager.getConfiguration(FlippingRsConfig.GROUP, WATCHLIST_KEY);
		return id == null || id.isEmpty() ? null : id;
	}

	void rememberWatchlist(String id)
	{
		configManager.setConfiguration(FlippingRsConfig.GROUP, WATCHLIST_KEY, id);
	}
}
