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
		description = "How the plugin talks to flippingrs.com",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Create one at flippingrs.com under Account, API keys. Choose the RuneLite plugin scope.",
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
		description = "Sends your Grand Exchange fills -- item, quantity, price, gp value, slot, world and time -- "
			+ "together with your IP address, to flippingrs.com, a third party server not controlled or verified "
			+ "by the RuneLite developers. Your RuneScape display name is not sent. Turn off to stop capturing "
			+ "and to stop contacting the server entirely. Trades that happen while it is off are discarded, not "
			+ "queued; anything already waiting is sent when you turn it back on.",
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
		description = "How long to batch fills before sending them. A busy flipper fills a slot every few seconds; batching keeps that to one request.",
		position = 3,
		section = connectionSection
	)
	@Range(min = 5, max = 600)
	default int syncSeconds()
	{
		return 30;
	}

	// The server address is deliberately not here either. It was once, as an
	// "advanced" setting for self-hosters, but a text box that redirects an API
	// key and every recorded trade is a liability out of all proportion to who
	// used it. It now lives in FlippingRsApi as a constant.

	// The game account is deliberately not here. It is stored per RuneScape
	// profile and picked in the side panel, so logging into an alt files its
	// trades under the right journal without anyone remembering to switch a
	// setting. A single global dropdown would silently file a main's flips
	// under an alt the first time someone forgot.
}
