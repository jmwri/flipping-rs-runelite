package com.flippingrs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
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
		examine = new ExaminePrices(client, config, chat, quotes::get, asked::add);

		final Quote whip = new Quote();
		whip.id = 4151;
		whip.instantSell = 1_480_000;
		whip.instantBuy = 1_520_000;
		whip.netMargin = 32_000;
		quotes.put(4151, whip);
	}

	private MenuOptionClicked examineOf(MenuAction action, int itemId)
	{
		final MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuAction()).thenReturn(action);
		when(event.getItemId()).thenReturn(itemId);
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

		examine.clicked(examineOf(MenuAction.EXAMINE_ITEM, 4151));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));

		final org.mockito.ArgumentCaptor<String> written =
			org.mockito.ArgumentCaptor.forClass(String.class);
		org.mockito.Mockito.verify(node).setValue(written.capture());
		assertTrue(written.getValue(), written.getValue().startsWith("A weapon from the abyss."));
		assertTrue(written.getValue(), written.getValue().contains("1,480,000"));
		assertTrue(written.getValue(), written.getValue().contains("1,520,000"));
		assertTrue(written.getValue(), written.getValue().contains("+32,000"));
	}

	/** An item on the ground is an item too. */
	@Test
	public void anItemOnTheGroundIsPricedAsWell()
	{
		final MessageNode node = node("A weapon from the abyss.");

		examine.clicked(examineOf(MenuAction.EXAMINE_ITEM_GROUND, 4151));
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
		examine.clicked(examineOf(MenuAction.EXAMINE_ITEM, 4151));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node("A weapon from the abyss.")));

		final MessageNode second = node("A sturdy oak tree.");
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, second));

		org.mockito.Mockito.verify(second, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
	}

	/** Clicking anything else clears the item, so it cannot be paired later. */
	@Test
	public void anyOtherClickForgetsTheItem()
	{
		examine.clicked(examineOf(MenuAction.EXAMINE_ITEM, 4151));
		examine.clicked(examineOf(MenuAction.WALK, 0));

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

		examine.clicked(examineOf(MenuAction.EXAMINE_ITEM, 1511));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));

		org.mockito.Mockito.verify(node, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
		assertEquals("but it is asked about", java.util.Collections.singletonList(1511), asked);
	}

	/** A message that is not an item examine is left alone. */
	@Test
	public void otherChatIsLeftAlone()
	{
		final MessageNode node = node("A sturdy oak tree.");

		examine.clicked(examineOf(MenuAction.EXAMINE_ITEM, 4151));
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

		examine.clicked(examineOf(MenuAction.EXAMINE_ITEM, 4151));
		examine.examined(message(ChatMessageType.ITEM_EXAMINE, node));

		org.mockito.Mockito.verify(node, org.mockito.Mockito.never())
			.setValue(org.mockito.ArgumentMatchers.anyString());
	}
}
