package com.flippingrs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(FlippingRsConfig.GROUP)
public interface FlippingRsConfig extends Config
{
	String GROUP = "flippingrs";

	@ConfigSection(
		name = "Connection",
		description = "Linking the plugin to your flippingrs.com account",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Paste the key from flippingrs.com. On the site, go to Account, then API keys, and create one for the RuneLite plugin.",
		position = 1,
		secret = true,
		section = connectionSection
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "enabled",
		name = "Record trades",
		description = "Sends your Grand Exchange trades to your flippingrs.com journal as they happen: the item, "
			+ "how many, the price, the slot and world, and when. It also sends your open offers and what your "
			+ "Grand Exchange history shows, so trades made while RuneLite was closed can be caught up. "
			+ "flippingrs.com is a third-party service not run or checked by the RuneLite team, and like any "
			+ "website it can see your IP address. Your character name is never sent. Switch this off and the "
			+ "plugin stops recording and stops talking to flippingrs.com altogether. Trades made while it is "
			+ "off are not recorded; anything already waiting is sent when you switch it back on.",
		position = 2,
		section = connectionSection
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncSeconds",
		name = "Send every (seconds)",
		description = "How long to wait between sends. Your trades are grouped up and sent together, so busy flipping does not mean constant sending. Nothing is lost while it waits.",
		position = 3,
		section = connectionSection
	)
	@Range(min = 5, max = 600)
	default int syncSeconds()
	{
		return 30;
	}

	@ConfigSection(
		name = "Grand Exchange",
		description = "What the plugin adds inside the Grand Exchange",
		position = 10
	)
	String exchangeSection = "exchange";

	@ConfigItem(
		keyName = "geMenuEntries",
		name = "Right-click entries",
		description = "Adds \"View item\" and \"Add to watchlist\" when you right-click an item in the Grand "
			+ "Exchange: your offer slots, the items beside them, the offer setup screen and your history. "
			+ "\"View item\" opens the item on flippingrs.com in your browser. \"Add to watchlist\" puts it on "
			+ "the watchlist in the sidebar. Neither one touches the game.",
		position = 11,
		section = exchangeSection
	)
	default boolean geMenuEntries()
	{
		return true;
	}

	@ConfigSection(
		name = "Developer",
		description = "Only read when RuneLite was started with --developer-mode",
		position = 20,
		closedByDefault = true
	)
	String developerSection = "developer";

	// The server address is a setting again, but only for a client started in
	// developer mode, which is how the plugin is run against a local server.
	// In a normal install it is ignored outright: a text box that redirects an
	// API key and every recorded trade is a liability out of all proportion to
	// who would use it, and ignoring it rather than hiding it means a value
	// left over from an older version cannot quietly do anything either.
	@ConfigItem(
		keyName = "baseUrl",
		name = "Server URL",
		description = "Where to send everything instead of https://flippingrs.com, for running against a local "
			+ "server. Only honoured when the client was started with --developer-mode; ignored otherwise.",
		position = 21,
		section = developerSection
	)
	default String baseUrl()
	{
		return "";
	}

	// The game account is deliberately not here. It is stored per RuneScape
	// profile and picked in the side panel, so logging into an alt files its
	// trades under the right journal without anyone remembering to switch a
	// setting. A single global dropdown would silently file a main's flips
	// under an alt the first time someone forgot.
}
