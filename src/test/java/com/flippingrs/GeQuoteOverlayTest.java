package com.flippingrs;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The overlay draws only on the offer setup screen, only for a watched item
 * with a quote. Everything else must draw nothing rather than a blank box.
 */
public class GeQuoteOverlayTest
{
	private final Client client = mock(Client.class);
	private final Widget setup = mock(Widget.class);
	private final Map<Integer, FlippingRsApi.Quote> watched = new HashMap<>();
	private GeQuoteOverlay overlay;

	@Before
	public void setUp()
	{
		overlay = new GeQuoteOverlay(client, watched::get);
		final FlippingRsApi.Quote whip = new FlippingRsApi.Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		whip.instantBuy = 1_520_000;
		watched.put(4151, whip);
	}

	@Test
	public void showsTheQuoteForAWatchedItemBeingSetUp()
	{
		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(setup);
		when(setup.isHidden()).thenReturn(false);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(4151);

		assertSame(watched.get(4151), overlay.visibleQuote());
	}

	@Test
	public void drawsNothingWhenTheSetupScreenIsNotOpen()
	{
		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(null);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(4151);
		assertNull(overlay.visibleQuote());

		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(setup);
		when(setup.isHidden()).thenReturn(true);
		assertNull(overlay.visibleQuote());
	}

	@Test
	public void drawsNothingForAnItemThatIsNotWatched()
	{
		when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(setup);
		when(setup.isHidden()).thenReturn(false);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(11802);

		assertNull(overlay.visibleQuote());
	}

	@Test
	public void thePricesAreExactToTheCoin()
	{
		assertEquals("1,480,000", FlippingRsPanel.exact(1_480_000));
		assertEquals("+9,600", FlippingRsPanel.signedExact(9_600));
		assertEquals("-500", FlippingRsPanel.signedExact(-500));
		assertEquals("0", FlippingRsPanel.signedExact(0));
	}
}
