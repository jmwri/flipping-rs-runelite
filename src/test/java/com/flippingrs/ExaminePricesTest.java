package com.flippingrs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.chat.ChatMessageManager;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Putting the site's prices on the end of an examine line.
 *
 * <p>The examine text says nothing about which item it is for, so the item
 * comes from the click that asked for it. Pairing the two is the whole
 * difference between a useful line and one that confidently prices the wrong
 * item, and it is not something the message itself can be checked against.
 *
 * <p>These pin how the client actually reports an examine, which is not how it
 * reads: examining an item in an interface is a plain op told apart by its
 * name, and the item is on the widget rather than on the event. An earlier
 * version of this class assumed a MenuAction of its own and read the id off
 * the event, and the tests agreed with it -- they were written from the same
 * assumption, so they passed while the feature did nothing at all.
 */
public class ExaminePricesTest
{
	private final Client client = mock(Client.class);
	private final FlippingRsConfig config = mock(FlippingRsConfig.class);
	private final ChatMessageManager chat = mock(ChatMessageManager.class);
	private final Map<Integer, Quote> quotes = new HashMap<>();
	private final List<Integer> asked = new ArrayList<>();
	private ExaminePrices examine;

	@Before
	public void setUp()
	{
		when(config.examinePrices()).thenReturn(true);
		examine = new ExaminePrices(client, config, chat, quotes::get, asked::add, id -> "Abyssal whip");

		final Quote whip = new Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		whip.instantBuy = 1_520_000;
		whip.netMargin = 32_000;
		quotes.put(4151, whip);
	}

	/** Examining an item in an interface: the inventory, the bank, the exchange. */
	/** The text without its colour tags; see GeSetupTextTest. */
	private static String plain(String text)
	{
		return text.replaceAll("</?col[^>]*>", "");
	}

	private MenuOptionClicked examineInWidget(int widgetId, int slot, int itemId)
	{
		final MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuOption()).thenReturn("Examine");
		when(event.getMenuAction()).thenReturn(MenuAction.CC_OP_LOW_PRIORITY);
		when(event.getParam1()).thenReturn(widgetId);
		when(event.getParam0()).thenReturn(slot);

		final Widget item = mock(Widget.class);
		when(item.getItemId()).thenReturn(itemId);
		final Widget container = mock(Widget.class);
		when(container.getChild(slot)).thenReturn(item);
		when(client.getWidget(widgetId)).thenReturn(container);
		return event;
	}

	/** Examining one lying on the ground, the only case that carries its own id. */
	private MenuOptionClicked examineOnGround(int itemId)
	{
		final MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuOption()).thenReturn("Examine");
		when(event.getMenuAction()).thenReturn(MenuAction.EXAMINE_ITEM_GROUND);
		when(event.getId()).thenReturn(itemId);
		return event;
	}

	/** Any other click. */
	private MenuOptionClicked somethingElse(String option, MenuAction action)
	{
		final MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuOption()).thenReturn(option);
		when(event.getMenuAction()).thenReturn(action);
		return event;
	}

	private ChatMessage message(ChatMessageType type, MessageNode node)
	{
		final ChatMessage event = mock(ChatMessage.class);
		when(event.getType()).thenReturn(type);
		when(event.getMessageNode()).thenReturn(node);
		return event;
	}

	private MessageNode node(String text)
	{
		final MessageNode node = mock(MessageNode.class);
		when(node.getValue()).thenReturn(text);
		return node;
	}

	@Test
	public void thePricesGoOnTheEndOfTheExamineLine()
	{
		final MessageNode node = node("A weapon from the abyss.");

		examine.clicked(examineInWidget(9764864, 3, 4151));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));

		final org.mockito.ArgumentCaptor<String> written =
			org.mockito.ArgumentCaptor.forClass(String.class);
		// The value, with the colours already in it. The format message is not
		// the way: update() is a no-op in this client, and the tokens it would
		// have resolved are only resolved for message types RuneLite has a
		// colour configured for -- which an item examine is not.
		org.mockito.Mockito.verify(node).setValue(written.capture());
		assertTrue(written.getValue(), plain(written.getValue()).startsWith("A weapon from the abyss."));
		assertTrue(written.getValue(), plain(written.getValue()).contains("1,480,000"));
		assertTrue(written.getValue(), plain(written.getValue()).contains("1,520,000"));
		assertTrue(written.getValue(), plain(written.getValue()).contains("+32,000"));
	}

	/**
	 * An item on the ground is an item too, and the one case where the event
	 * carries the item itself rather than naming a widget.
	 */
	@Test
	public void anItemOnTheGroundIsPricedAsWell()
	{
		final MessageNode node = node("A weapon from the abyss.");

		examine.clicked(examineOnGround(4151));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));

		org.mockito.Mockito.verify(node).setValue(org.mockito.ArgumentMatchers.anyString());
	}

	/**
	 * One message per click. Without that, the next examine message the client
	 * produced -- for an object, an NPC, another item -- would be handed the
	 * last item's prices.
	 */
	@Test
	public void oneClickPricesOneMessage()
	{
		examine.clicked(examineInWidget(9764864, 3, 4151));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node("A weapon from the abyss.")));

		final MessageNode second = node("A sturdy oak tree.");
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, second));

		org.mockito.Mockito.verify(second, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
	}

	/**
	 * An op on the same item that is not an examine forgets it.
	 *
	 * <p>This is the case the name check exists for. Every other thing you can
	 * do to an item in an interface -- wield it, drop it, use it -- arrives as
	 * the same menu action from the same widget, and only the name tells them
	 * apart. Without that check, wielding something would have queued its
	 * prices onto whatever examine line came next.
	 */
	@Test
	public void anOpOnTheSameItemThatIsNotAnExamineForgetsIt()
	{
		examine.clicked(examineInWidget(9764864, 3, 4151));
		examine.clicked(somethingElse("Wield", MenuAction.CC_OP_LOW_PRIORITY));

		assertEquals(-1, examine.examinedForTest());

		final MessageNode node = node("A weapon from the abyss.");
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));
		org.mockito.Mockito.verify(node, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
	}

	/**
	 * An item nobody has priced says nothing now and is asked about, so the
	 * next examine of it can answer. A price arriving in the chat box after
	 * the line it belongs to has scrolled away is a line about nothing.
	 */
	@Test
	public void anUnpricedItemIsAskedAboutRatherThanGuessedAt()
	{
		final MessageNode node = node("A sturdy oak log.");

		examine.clicked(examineInWidget(9764864, 5, 1511));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));

		org.mockito.Mockito.verify(node, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
		assertEquals("but it is asked about", java.util.Collections.singletonList(1511), asked);
	}

	/**
	 * The two ways the client reports an examine, and the ways it does not.
	 *
	 * <p>Pinned directly, because this is what was wrong: the class looked for
	 * a MenuAction of its own and read the id off the event, and neither of
	 * those is how it arrives.
	 */
	@Test
	public void anExamineIsRecognisedTheWayTheClientReportsOne()
	{
		assertEquals("an item in an interface, resolved off the widget",
			4151, ExaminePrices.itemFor(examineInWidget(9764864, 3, 4151), client::getWidget));
		assertEquals("an item on the ground, carried on the event",
			4151, ExaminePrices.itemFor(examineOnGround(4151), client::getWidget));
		assertEquals("another op on the same widget is not an examine",
			-1, ExaminePrices.itemFor(somethingElse("Wield", MenuAction.CC_OP_LOW_PRIORITY),
				client::getWidget));
		assertEquals("and neither is examining something that is not an item",
			-1, ExaminePrices.itemFor(somethingElse("Examine", MenuAction.EXAMINE_NPC),
				client::getWidget));
	}

	/** A widget the client will not resolve is not an item. */
	@Test
	public void anExamineOnAWidgetThatIsNotThereIsNotAnItem()
	{
		final MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuOption()).thenReturn("Examine");
		when(event.getMenuAction()).thenReturn(MenuAction.CC_OP_LOW_PRIORITY);
		when(event.getParam1()).thenReturn(1234);
		when(event.getParam0()).thenReturn(0);

		assertEquals(-1, ExaminePrices.itemFor(event, client::getWidget));
	}

	/**
	 * A price that arrives after the line it belonged to is said as its own
	 * line, naming the item.
	 *
	 * <p>The first examine of an item nobody has asked the site about cannot
	 * be answered on the spot. Saying nothing at all was the first attempt at
	 * that, and it reads exactly like a broken feature: you examine a thing,
	 * and the plugin you installed to price things says nothing.
	 */
	@Test
	public void aPriceThatArrivesLateIsSaidWithTheItemsName()
	{
		examine.priced(4151);

		final org.mockito.ArgumentCaptor<net.runelite.client.chat.QueuedMessage> said =
			org.mockito.ArgumentCaptor.forClass(net.runelite.client.chat.QueuedMessage.class);
		org.mockito.Mockito.verify(chat).queue(said.capture());
		final String line = plain(said.getValue().getValue());
		assertTrue(line, line.contains("Abyssal whip"));
		assertTrue(line, line.contains("1,480,000"));
	}

	/** And an item the server had no price for says nothing at all. */
	@Test
	public void aPriceThatNeverArrivedSaysNothing()
	{
		examine.priced(1511);

		org.mockito.Mockito.verify(chat, org.mockito.Mockito.never())
			.queue(org.mockito.ArgumentMatchers.any());
	}

	/** A message that is not an item examine is left alone. */
	@Test
	public void otherChatIsLeftAlone()
	{
		final MessageNode node = node("A sturdy oak tree.");

		examine.clicked(examineInWidget(9764864, 3, 4151));
		examine.examined(message(ChatMessageType.OBJECT_EXAMINE, node));

		org.mockito.Mockito.verify(node, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
	}

	/** And the setting turns it off entirely. */
	@Test
	public void theSettingTurnsItOff()
	{
		when(config.examinePrices()).thenReturn(false);
		final MessageNode node = node("A weapon from the abyss.");

		examine.clicked(examineInWidget(9764864, 3, 4151));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));

		org.mockito.Mockito.verify(node, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
	}
}
