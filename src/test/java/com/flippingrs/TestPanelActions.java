package com.flippingrs;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;

/**
 * A {@link PanelActions} whose hooks a test can replace one at a time.
 *
 * <p>The panel takes its actions in the constructor, so a test that only cares
 * about one button still has to supply the rest. Every field here starts as a
 * no-op and a test assigns the one it wants to watch, which keeps a panel test
 * about the panel rather than about the eleven things it is not exercising.
 */
final class TestPanelActions implements PanelActions
{
	Runnable sendNow = () ->
	{
	};
	Runnable retrySetAside = () ->
	{
	};
	Runnable reconnect = () ->
	{
	};
	Runnable accountChosen = () ->
	{
	};
	Runnable watchlistChosen = () ->
	{
	};
	IntConsumer openItem = id ->
	{
	};
	IntConsumer removeItem = id ->
	{
	};
	Runnable findFlips = () ->
	{
	};
	Close closePosition = (id, price, qty) ->
	{
	};
	Consumer<String> deletePosition = id ->
	{
	};
	Runnable shown = () ->
	{
	};
	Runnable hidden = () ->
	{
	};

	/** The close hook, with the panel's own three arguments. */
	interface Close
	{
		void close(String positionId, long sellPrice, @Nullable Long sellQty);
	}

	@Override
	public void sendNow()
	{
		sendNow.run();
	}

	@Override
	public void retrySetAside()
	{
		retrySetAside.run();
	}

	@Override
	public void reconnect()
	{
		reconnect.run();
	}

	@Override
	public void accountChosen()
	{
		accountChosen.run();
	}

	@Override
	public void watchlistChosen()
	{
		watchlistChosen.run();
	}

	@Override
	public void openItem(int itemId)
	{
		openItem.accept(itemId);
	}

	@Override
	public void removeItem(int itemId)
	{
		removeItem.accept(itemId);
	}

	@Override
	public void findFlips()
	{
		findFlips.run();
	}

	@Override
	public void closePosition(String positionId, long sellPrice, @Nullable Long sellQty)
	{
		closePosition.close(positionId, sellPrice, sellQty);
	}

	@Override
	public void deletePosition(String positionId)
	{
		deletePosition.accept(positionId);
	}

	@Override
	public void shown()
	{
		shown.run();
	}

	@Override
	public void hidden()
	{
		hidden.run();
	}
}
