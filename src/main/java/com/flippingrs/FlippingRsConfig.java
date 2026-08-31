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

	@ConfigSection(
		name = "Advanced",
		description = "You should not normally need to change these",
		position = 10,
		closedByDefault = true
	)
	String advancedSection = "advanced";

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
		description = "Turn off to stop sending. Trades that happen while it is off are not recorded at all, not queued.",
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

	@ConfigItem(
		keyName = "baseUrl",
		name = "Base URL",
		description = "The FlippingRS instance to send to. Only change this if you are running your own.",
		position = 11,
		section = advancedSection
	)
	default String baseUrl()
	{
		return "https://flippingrs.com";
	}

	// The game account is deliberately not here. It is stored per RuneScape
	// profile and picked in the side panel, so logging into an alt files its
	// trades under the right journal without anyone remembering to switch a
	// setting. A single global dropdown would silently file a main's flips
	// under an alt the first time someone forgot.
}
