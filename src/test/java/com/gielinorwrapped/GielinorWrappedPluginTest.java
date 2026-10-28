package com.gielinorwrapped;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GielinorWrappedPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GielinorWrappedPlugin.class);
		RuneLite.main(args);
	}
}
