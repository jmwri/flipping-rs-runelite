package com.flippingrs;

import java.util.function.Consumer;

/**
 * Hands a panel update to the Swing thread, if the panel still exists.
 *
 * <p>The plugin owns the panel and nulls it on shutdown; everything else only
 * ever asks for an update through this, so nothing but the plugin needs to
 * know whether there is a panel to update right now.
 */
@FunctionalInterface
interface PanelUpdates
{
	void onPanel(Consumer<FlippingRsPanel> action);
}
