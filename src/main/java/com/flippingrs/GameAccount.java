package com.flippingrs;

/** One row of the game-account picker. */
public class GameAccount
{
	String id;
	String label;
	boolean isDefault;

	@Override
	public String toString()
	{
		// This is what the combo box renders.
		return Wire.plain(label == null || label.isEmpty() ? id : label);
	}
}
