package com.flippingrs;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite with this plugin loaded, for development. Run it with
 * {@code ./gradlew run}.
 */
public class FlippingRsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FlippingRsPlugin.class);
		RuneLite.main(args);
	}
}
