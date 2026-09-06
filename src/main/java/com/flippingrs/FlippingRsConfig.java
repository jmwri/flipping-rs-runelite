package com.flippingrs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
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
			+ "off are not recorded as they happen; anything already waiting is sent when you switch it back on. "
			+ "Once it is back on, the catch-up from your open offers and your Grand Exchange history can still "
			+ "add an offer that completed while it was off, saved without a time.",
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

	@ConfigItem(
		keyName = "setupOverlay",
		name = "Prices in the exchange",
		description = "Shows flippingrs.com's prices where you need them. On the offer setup screen: the exact buy "
			+ "and sell prices, the margin, how old the prices are, and how much of the buy limit you have left "
			+ "if your plan tracks that. And on any item the exchange shows, point at it and the game's own "
			+ "hover box gains the same, plus how far your offer is from the side you are trading if it is "
			+ "one of yours. Nothing the exchange draws is replaced or moved. Works for "
			+ "any item, not only the ones on your watchlist; nothing is shown for an item the site has no price "
			+ "for. Buy limits count only the trades your journal knows about, so an item you bought before "
			+ "installing the plugin, or on another client, can show more room left than you really have.",
		position = 12,
		section = exchangeSection
	)
	default boolean setupOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "examinePrices",
		name = "Prices when you examine",
		description = "Adds flippingrs.com's buy and sell prices and the margin to the end of an item's examine "
			+ "line. Examine is the question the game already has for \"what is this\", and it is asked from the "
			+ "inventory, the bank and the ground -- places the Grand Exchange never sees. An item nobody has "
			+ "priced yet says nothing the first time and is answered the next.",
		position = 13,
		section = exchangeSection
	)
	default boolean examinePrices()
	{
		return true;
	}

	@ConfigSection(
		name = "Notifications",
		description = "When the plugin should get your attention",
		position = 15
	)
	String notificationSection = "notifications";

	// Only two, and only for things you would otherwise never learn. The
	// sidebar is where the plugin explains itself, and a flipper keeps the
	// exchange open and the sidebar shut -- so anything that goes wrong with
	// recording is said into a panel nobody is looking at. That is worth a
	// notification. An offer filling is not, by default: the client already
	// tells you, and a busy flipper would get one every few seconds.

	@ConfigItem(
		keyName = "notifyProblems",
		name = "Trades that couldn't be recorded",
		description = "Tells you when flippingrs.com would not record a trade. The sidebar says so too, but a "
			+ "flipper keeps it shut, and a trade missing from your journal is not something you want to find "
			+ "out about days later.",
		position = 16,
		section = notificationSection
	)
	default Notification notifyProblems()
	{
		return Notification.ON;
	}

	@ConfigItem(
		keyName = "notifyOfferComplete",
		name = "An offer finishing",
		description = "Tells you when one of your Grand Exchange offers finishes. Off by default: the client "
			+ "already shows it, and a fast flipper would get one of these every few seconds.",
		position = 17,
		section = notificationSection
	)
	default Notification notifyOfferComplete()
	{
		return Notification.OFF;
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

	// The game account is deliberately not here. It is stored against the
	// RuneScape account and picked in the side panel, so logging into an alt
	// files its trades under the right journal without anyone remembering to
	// switch a setting. A single global dropdown would silently file a main's
	// flips under an alt the first time someone forgot.
}
