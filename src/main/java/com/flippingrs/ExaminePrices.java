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
import net.runelite.api.widgets.Widget;
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
 * <p>Which click that is, is not obvious. Examining an item in the inventory,
 * the bank or the exchange is not {@code EXAMINE_ITEM} -- it is a plain
 * interface op, {@code CC_OP_LOW_PRIORITY}, told apart from every other op on
 * that widget by its name. And the item it was on is not on the event either:
 * it has to be read off the widget the op names. Only an item lying on the
 * ground carries its own id. This is the same pair of cases RuneLite's own
 * Examine plugin handles, and for the same reason -- there is no third way to
 * ask.
 *
 * <p>Client thread. The lookup is a cache read; an item nobody has priced yet
 * is asked about and answered on the next examine rather than kept waiting
 * for, because a price arriving in the chat box seconds after the line it
 * belongs to is a line about nothing in particular.
 */
@Slf4j
class ExaminePrices
{
	/** The name of the op, which is the only thing that marks it as an examine. */
	private static final String EXAMINE = "Examine";

	/** Colours for the added text, written as the game reads them. */
	private static final String MUTED = "9f9f9f";
	private static final String VALUE = "ffffff";
	private static final String GOOD = "4caf50";
	private static final String BAD = "d32f2f";
	private static final String NAME = "ff981f";

	private final Client client;
	private final FlippingRsConfig config;
	private final ChatMessageManager chat;
	private final IntFunction<Quote> quoteFor;
	/** Asks for a price for an item nobody has one for yet. */
	private final IntConsumer wanted;
	/** Resolves an item's name, for a price that arrives after its line. */
	private final IntFunction<String> itemName;

	/** The item the last examine was asked about, or -1. */
	private int examined = -1;

	ExaminePrices(Client client, FlippingRsConfig config, ChatMessageManager chat,
		IntFunction<Quote> quoteFor, IntConsumer wanted, IntFunction<String> itemName)
	{
		this.client = client;
		this.config = config;
		this.chat = chat;
		this.quoteFor = quoteFor;
		this.wanted = wanted;
		this.itemName = itemName;
	}

	/**
	 * Notes which item an examine was asked about.
	 *
	 * <p>Anything that is not an examine clears it, so a stale id can never be
	 * paired with the next examine message that happens along.
	 */
	void clicked(MenuOptionClicked event)
	{
		examined = itemFor(event, client::getWidget);
	}

	/**
	 * The item an examine click was on, or -1 if the click was not one.
	 *
	 * <p>Takes the widget lookup rather than reaching for it, so the two ways
	 * an examine arrives can be told apart without a client running. Getting
	 * this wrong is silent: the click is simply never matched, and the examine
	 * line comes out exactly as the game wrote it.
	 *
	 * @param widgets resolves a component id, i.e. {@code Client::getWidget}
	 */
	static int itemFor(MenuOptionClicked event, IntFunction<Widget> widgets)
	{
		// The op's name is what separates an examine from every other thing
		// that can be done to an item in an interface. There is no menu action
		// of its own for it.
		if (!EXAMINE.equals(event.getMenuOption()))
		{
			return -1;
		}
		final MenuAction action = event.getMenuAction();
		if (action == MenuAction.EXAMINE_ITEM_GROUND)
		{
			// The only case where the event carries the item itself.
			return event.getId();
		}
		if (action != MenuAction.CC_OP && action != MenuAction.CC_OP_LOW_PRIORITY)
		{
			return -1;
		}
		// An op on an interface: the widget it was on, and the slot within it.
		final Widget widget = widgets.apply(event.getParam1());
		if (widget == null)
		{
			return -1;
		}
		final int slot = event.getParam0();
		if (slot >= 0)
		{
			final Widget item = widget.getChild(slot);
			if (item != null && item.getItemId() > 0)
			{
				return item.getItemId();
			}
		}
		// A widget that is one item rather than a grid of them.
		return widget.getItemId() > 0 ? widget.getItemId() : -1;
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
				// Nobody has asked the site about this item yet. Ask now; the
				// answer follows as its own line, naming the item, rather than
				// this examine going unanswered.
				wanted.accept(itemId);
				return;
			}
			final MessageNode node = event.getMessageNode();
			if (node == null)
			{
				return;
			}
			// Straight into the value, with the colours already in it. The
			// format message is not the way: ChatMessageManager.update is a
			// no-op in this client, and what it would have resolved --
			// RuneLite's <colNORMAL> tokens -- is only resolved for message
			// types it has a configured colour for. An item examine is not one
			// of them, so the tokens arrived on screen as their own text.
			node.setValue(node.getValue() + suffix(quote));
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
	 * <p>Coloured with the colour itself rather than with RuneLite's
	 * {@code <colNORMAL>} tokens. Those are only turned into colours for the
	 * message types RuneLite has a colour configured for, and an item examine
	 * is not one of them -- so the tokens went to the chat box as their own
	 * text. A literal colour tag is what the game's text renderer reads, and
	 * it needs nothing to have substituted anything first.
	 *
	 * <p>Static and returning the built string, so the wording is pinned by a
	 * test rather than by running a client.
	 */
	static String suffix(Quote quote)
	{
		return colour(" Buy ", MUTED) + colour(FlippingRsPanel.exact(quote.getBuyAt()), VALUE)
			+ colour(" · Sell ", MUTED) + colour(FlippingRsPanel.exact(quote.getSellAt()), VALUE)
			+ colour(" · Margin ", MUTED)
			+ colour(FlippingRsPanel.signedExact(quote.getNetMargin()),
				quote.getNetMargin() >= 0 ? GOOD : BAD);
	}

	/** One run of text in one colour, as the game's own text renderer reads it. */
	static String colour(String text, String hex)
	{
		return "<col=" + hex + ">" + text + "</col>";
	}

	/**
	 * A price that arrived after the line it belonged to.
	 *
	 * <p>The first examine of an item nobody has asked the site about cannot
	 * be answered on the spot: there is nothing to answer with until a request
	 * comes back. Saying nothing at all was the first attempt at that and it
	 * reads exactly like a broken feature -- you examine a thing, and the
	 * plugin you installed to price things says nothing.
	 *
	 * <p>So the answer follows a moment later, as its own line, naming the
	 * item. Naming it is what makes a late line readable rather than a price
	 * floating free of anything: by the time it lands the examine text may
	 * have a couple of lines above it.
	 */
	void priced(int itemId)
	{
		try
		{
			if (!config.examinePrices())
			{
				return;
			}
			final Quote quote = quoteFor.apply(itemId);
			if (quote == null)
			{
				return;
			}
			// value, not runeLiteFormattedMessage, for the same reason: the
			// colours are already in the string and nothing has to substitute
			// a token to make them appear.
			chat.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.value(colour(itemName.apply(itemId), NAME) + suffix(quote))
				.build());
		}
		catch (RuntimeException e)
		{
			log.debug("could not say what an examined item is worth", e);
		}
	}

	/** The item the next examine message will be answered for, for a test. */
	int examinedForTest()
	{
		return examined;
	}
}
