package net.runelite.client.plugins.microbot.huntertrapper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("huntertrapper")
public interface HunterTrapperConfig extends Config
{
    @ConfigItem(
            keyName = "bankOnFull",
            name = "Bank when inventory is full",
            description = "Run to the bank and deposit when inventory is full.",
            position = 0
    )
    default boolean bankOnFull()
    {
        return true;
    }

    @ConfigItem(
            keyName = "enableLooting",
            name = "Loot ground supplies",
            description = "Loot your own ropes/nets on the ground near traps.",
            position = 1
    )
    default boolean enableLooting()
    {
        return true;
    }
}
