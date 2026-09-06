package com.flippingrs;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * The site's prices on the end of an item's examine line.
 *
 * <p>Examine is the question the game already has for "what is this", and it
 * is asked from the inventory, the bank and the ground -- places the exchange
 * never sees. Answering it with what the item is worth costs no new gesture
 * and no new screen.
 *
 * <p>The examine text says nothing about which item it is for, so the item is
 * taken from the click that asked. The two arrive in the same tick, in that
 * order, which is what makes pairing them safe; a click whose message never
 * came is forgotten on the next one rather than kept to be paired with
 * somebody else's.
 *
 * <p>Client thread. The lookup is a cache read; an item nobody has priced yet
 * is asked about and answered on the next examine rather than kept waiting
 * for, because a price arriving in the chat box seconds after the line it
 * belongs to is a line about nothing in particular.
 */
@Slf4j
class ExaminePrices
{
	private final Client client;
	private final FlippingRsConfig config;
	private final ChatMessageManager chat;
	private final IntFunction<Quote> quoteFor;
	/** Asks for a price for an item nobody has one for yet. */
	private final IntConsumer wanted;

	/** The item the last examine was asked about, or -1. */
	private int examined = -1;

	ExaminePrices(Client client, FlippingRsConfig config, ChatMessageManager chat,
		IntFunction<Quote> quoteFor, IntConsumer wanted)
	{
		this.client = client;
		this.config = config;
		this.chat = chat;
		this.quoteFor = quoteFor;
		this.wanted = wanted;
	}

	/**
	 * Notes which item an examine was asked about.
	 *
	 * <p>Both kinds: an item in an interface -- inventory, bank, the exchange
	 * itself -- and one lying on the ground. Anything else clears it, so a
	 * stale id can never be paired with the next examine message that happens
	 * along.
	 */
	void clicked(MenuOptionClicked event)
	{
		final MenuAction action = event.getMenuAction();
		if (action == MenuAction.EXAMINE_ITEM || action == MenuAction.EXAMINE_ITEM_GROUND)
		{
			examined = event.getItemId();
			return;
		}
		examined = -1;
	}

	/**
	 * Puts the prices on the end of the examine line.
	 *
	 * <p>On the end of it, rather than as a line of its own: examine is one
	 * line today and two would be twice the chat for the same question.
	 *
	 * <p>Never throws. It runs from the event bus on the game thread, where an
	 * exception becomes an uncaught plugin error on every examine.
	 */
	void examined(ChatMessage event)
	{
		try
		{
			if (event.getType() != ChatMessageType.ITEM_EXAMINE || !config.examinePrices())
			{
				return;
			}
			final int itemId = examined;
			// One message per click. Without this, any later examine message
			// the client produced -- an object, another item -- would be given
			// the last item's prices.
			examined = -1;
			if (itemId <= 0)
			{
				return;
			}
			final Quote quote = quoteFor.apply(itemId);
			if (quote == null)
			{
				// Nobody has asked the site about this item. Ask now, so the
				// next examine of it can answer; saying nothing is better than
				// a price arriving in the chat box after the line it belongs
				// to has scrolled away from it.
				wanted.accept(itemId);
				return;
			}
			final MessageNode node = event.getMessageNode();
			if (node == null)
			{
				return;
			}
			node.setValue(node.getValue() + suffix(quote));
			chat.update(node);
			client.refreshChat();
		}
		catch (RuntimeException e)
		{
			log.debug("could not put prices on an examine line", e);
		}
	}

	/**
	 * What gets added to the line: the two prices and the margin.
	 *
	 * <p>Static and returning the built string, so the wording is pinned by a
	 * test rather than by running a client.
	 */
	static String suffix(Quote quote)
	{
		return new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append(" Buy ")
			.append(ChatColorType.HIGHLIGHT)
			.append(FlippingRsPanel.exact(quote.getBuyAt()))
			.append(ChatColorType.NORMAL)
			.append(" · Sell ")
			.append(ChatColorType.HIGHLIGHT)
			.append(FlippingRsPanel.exact(quote.getSellAt()))
			.append(ChatColorType.NORMAL)
			.append(" · Margin ")
			.append(ChatColorType.HIGHLIGHT)
			.append(FlippingRsPanel.signedExact(quote.getNetMargin()))
			.build();
	}

	/**
	 * The same, as a line of its own, for an item examined from somewhere the
	 * chat did not produce a message to hang it off.
	 *
	 * <p>Unused today and kept out of the click path deliberately: it exists
	 * for the caller that wants to say something rather than append it.
	 */
	void say(String itemName, Quote quote)
	{
		chat.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append(itemName)
				.append(suffix(quote))
				.build())
			.build());
	}

	/** The item the next examine message will be answered for, for a test. */
	int examinedForTest()
	{
		return examined;
	}
}
