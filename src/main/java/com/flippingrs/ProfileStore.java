package com.flippingrs;

import com.google.gson.Gson;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * What the plugin remembers between sessions, in RuneLite's config.
 *
 * <p>Two of the three things here are a character's rather than a person's:
 * the baseline for each exchange slot, which is what stops a login from
 * re-reporting every offer still on the exchange, and which FlippingRS
 * journal this character files under. The third, which watchlist the
 * right-click entry adds to, is a plain plugin setting: a watchlist is a
 * person's, not a character's.
 *
 * <p>The journal choice is per character so an alt gets its own journal
 * without anyone remembering to change a dropdown before logging in. Getting
 * it wrong mixes two accounts' numbers together, and since buy limits are
 * tracked per game account, the damage is not cosmetic. It is picked in the
 * side panel, not the settings, for the same reason: a single global dropdown
 * would silently file a main's flips under an alt the first time someone
 * forgot.
 *
 * <p>The slot baselines live in the active RuneScape profile, because they are
 * only ever read for the character that is logged in. The journal choice is
 * keyed by account hash instead, because it is not: sending an alt's queued
 * fills means knowing which journal they belong to while somebody else is
 * logged in.
 */
@Slf4j
final class ProfileStore
{
	/** Config key prefix for the per-slot baseline. */
	static final String OFFER_KEY = "offer";
	/**
	 * Config key prefix for the chosen FlippingRS game account, per RuneScape
	 * account hash. Also the whole key of the per-profile setting older
	 * versions wrote, which {@link #migrateChosenAccount} reads once.
	 */
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

	/**
	 * The FlippingRS journal a RuneScape account files under, or null if none
	 * has been chosen for it.
	 *
	 * <p>Keyed by account hash rather than held in the active RuneScape
	 * profile, so that it can be read for an account that is not the one
	 * logged in. That is what lets a session send an alt's queued fills:
	 * the queue is per account, and without a journal to file them under they
	 * would otherwise wait on disk until that character next logged in, which
	 * could be never.
	 *
	 * <p>The account hash is the character, which is what the RuneScape
	 * profile was standing in for, so this is the same choice stored somewhere
	 * it can be looked up. {@link #migrateChosenAccount} moves an older
	 * install's choice across.
	 */
	@Nullable
	String chosenAccountFor(long accountHash)
	{
		final String id = configManager.getConfiguration(FlippingRsConfig.GROUP, accountKey(accountHash));
		return id == null || id.isEmpty() ? null : id;
	}

	void rememberChosenAccountFor(long accountHash, String id)
	{
		configManager.setConfiguration(FlippingRsConfig.GROUP, accountKey(accountHash), id);
	}

	void forgetChosenAccountFor(long accountHash)
	{
		configManager.unsetConfiguration(FlippingRsConfig.GROUP, accountKey(accountHash));
	}

	/**
	 * Moves the choice an older version stored in the active RuneScape profile
	 * to the per-account key this one reads.
	 *
	 * <p>Called with the account that is logged in, since the profile config is
	 * only readable for that one. Does nothing once there is a choice under the
	 * new key, so a user who has since picked a different journal keeps it. The
	 * old key is left where it is: it is one string, and removing it would mean
	 * a downgrade silently loses the setting.
	 */
	void migrateChosenAccount(long accountHash)
	{
		if (chosenAccountFor(accountHash) != null)
		{
			return;
		}
		final String legacy = configManager.getRSProfileConfiguration(FlippingRsConfig.GROUP, ACCOUNT_KEY);
		if (legacy == null || legacy.isEmpty())
		{
			return;
		}
		log.debug("moving this character's journal choice to the per-account key");
		rememberChosenAccountFor(accountHash, legacy);
	}

	/** Where one account's journal choice is kept. */
	private static String accountKey(long accountHash)
	{
		return ACCOUNT_KEY + "." + accountHash;
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
