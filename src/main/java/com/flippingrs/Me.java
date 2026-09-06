package com.flippingrs;

/** The key's owner, as much as the Account tab shows. */
public class Me
{
	String displayName;
	String effectiveTier;
	boolean onTrial;
	int trialDaysLeft;

	/** "Pro plan", "Pro trial, 5 days left", "Free plan". */
	public String describePlan()
	{
		final String tier = effectiveTier == null || effectiveTier.isEmpty() ? "unknown" : effectiveTier;
		final String name = Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
		if (onTrial)
		{
			return name + " trial, " + trialDaysLeft + (trialDaysLeft == 1 ? " day left" : " days left");
		}
		return name + " plan";
	}
}
