package com.flippingrs;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two full-page offer screens, written into rather than drawn on.
 *
 * <p>The setup page, where an offer is priced, and the status page you get by
 * clicking one you have already placed, where the question is whether the
 * price you chose is still the right one. They are the same screen twice as
 * far as this is concerned, and the client builds them together and hides the
 * one you are not looking at -- which is the whole reason they need separating
 * here. Both descriptions exist at all times, and both hold text.
 */
public class GeOfferScreensTest
{
	private static final int WHIP = 4151;
	private static final int SHIELD = 1540;

	private final Client client = mock(Client.class);
	private final FlippingRsConfig config = mock(FlippingRsConfig.class);
	private final Map<Integer, Quote> prices = new HashMap<>();

	private GeOfferText text;

	private static Quote priced(int itemId, int buy, int sell)
	{
		final Quote quote = new Quote();
		quote.id = itemId;
		quote.instantSell = buy;
		quote.instantBuy = sell;
		quote.netMargin = sell - buy;
		quote.dataAgeSeconds = -1;
		return quote;
	}

	/** A description line the client wrote, which remembers what is put in it. */
	private static Widget description(String said)
	{
		final Widget widget = mock(Widget.class);
		final String[] text = {said};
		when(widget.isHidden()).thenReturn(false);
		when(widget.getText()).thenAnswer(call -> text[0]);
		when(widget.setText(anyString())).thenAnswer(call ->
		{
			text[0] = call.getArgument(0);
			return widget;
		});
		return widget;
	}

	/** The status page, which carries the item of the offer being looked at. */
	private static Widget statusPageShowing(int itemId)
	{
		final Widget item = mock(Widget.class);
		when(item.isHidden()).thenReturn(false);
		when(item.getItemId()).thenReturn(itemId);
		when(item.getBounds()).thenReturn(new Rectangle(20, 20, 32, 32));

		final Widget page = mock(Widget.class);
		when(page.isHidden()).thenReturn(false);
		when(page.getItemId()).thenReturn(-1);
		when(page.getBounds()).thenReturn(new Rectangle(10, 10, 300, 200));
		when(page.getDynamicChildren()).thenReturn(new Widget[]{item});
		return page;
	}

	@Before
	public void setUp()
	{
		when(config.setupOverlay()).thenReturn(true);
		when(client.getWidget(anyInt())).thenReturn(null);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(0);
		text = new GeOfferText(client, config, prices::get);
	}

	/**
	 * Each page says what its own item is worth.
	 *
	 * <p>The failure this guards against is one record of "the line being
	 * added to" shared between them, which hands the setup page's text to the
	 * status page's description on the way past -- and prices an offer you
	 * placed yesterday at whatever you last typed into the search box.
	 */
	@Test
	public void eachPageGetsThePricesForItsOwnItem()
	{
		prices.put(WHIP, priced(WHIP, 1_480_000, 1_520_000));
		prices.put(SHIELD, priced(SHIELD, 100, 140));

		final Widget setup = description("A weapon from the abyss.");
		final Widget status = description("A medium shield.");
		when(client.getWidget(InterfaceID.GeOffers.SETUP_DESC)).thenReturn(setup);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS_DESC)).thenReturn(status);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(WHIP);
		// Built before the stubbing, not inside it: Mockito counts the when()
		// calls this makes as a stub begun and not finished.
		final Widget page = statusPageShowing(SHIELD);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS)).thenReturn(page);

		text.update();

		assertTrue(setup.getText(), setup.getText().startsWith("A weapon from the abyss.<br>"));
		assertTrue(setup.getText(), setup.getText().contains("1,480,000"));
		assertTrue("the status page is about the offer, not the search box",
			status.getText().contains("140"));
		assertTrue(status.getText(), status.getText().startsWith("A medium shield.<br>"));
	}

	/** The status page is written into exactly as the setup page is. */
	@Test
	public void theStatusPageReadsTheSameAsTheSetupPage()
	{
		prices.put(WHIP, priced(WHIP, 1_480_000, 1_520_000));

		final Widget setup = description("A weapon from the abyss.");
		final Widget status = description("A weapon from the abyss.");
		when(client.getWidget(InterfaceID.GeOffers.SETUP_DESC)).thenReturn(setup);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS_DESC)).thenReturn(status);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(WHIP);
		// Built before the stubbing, not inside it: Mockito counts the when()
		// calls this makes as a stub begun and not finished.
		final Widget page = statusPageShowing(WHIP);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS)).thenReturn(page);

		text.update();

		assertEquals(setup.getText(), status.getText());
	}

	/**
	 * A page with no price for its item keeps the description the game wrote,
	 * and does not keep the other page's.
	 */
	@Test
	public void apageWithNoPriceIsLeftAsTheGameWroteIt()
	{
		prices.put(WHIP, priced(WHIP, 1_480_000, 1_520_000));

		final Widget setup = description("A weapon from the abyss.");
		final Widget status = description("A medium shield.");
		when(client.getWidget(InterfaceID.GeOffers.SETUP_DESC)).thenReturn(setup);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS_DESC)).thenReturn(status);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(WHIP);
		// Nobody has a price for the shield yet, which is every item before
		// the first fetch lands.
		// Built before the stubbing, not inside it: Mockito counts the when()
		// calls this makes as a stub begun and not finished.
		final Widget page = statusPageShowing(SHIELD);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS)).thenReturn(page);

		text.update();

		assertEquals("A medium shield.", status.getText());
		assertTrue(setup.getText(), setup.getText().contains("1,480,000"));
	}

	/** And switching the setting off takes both additions off with it. */
	@Test
	public void turningItOffPutsBothDescriptionsBack()
	{
		prices.put(WHIP, priced(WHIP, 1_480_000, 1_520_000));

		final Widget setup = description("A weapon from the abyss.");
		final Widget status = description("A weapon from the abyss.");
		when(client.getWidget(InterfaceID.GeOffers.SETUP_DESC)).thenReturn(setup);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS_DESC)).thenReturn(status);
		when(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH)).thenReturn(WHIP);
		// Built before the stubbing, not inside it: Mockito counts the when()
		// calls this makes as a stub begun and not finished.
		final Widget page = statusPageShowing(WHIP);
		when(client.getWidget(InterfaceID.GeOffers.DETAILS)).thenReturn(page);
		text.update();

		when(config.setupOverlay()).thenReturn(false);
		text.update();

		assertEquals("A weapon from the abyss.", setup.getText());
		assertEquals("A weapon from the abyss.", status.getText());
	}
}
