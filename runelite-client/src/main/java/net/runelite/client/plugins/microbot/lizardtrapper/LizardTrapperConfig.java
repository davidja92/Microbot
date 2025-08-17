package net.runelite.client.plugins.microbot.lizardtrapper;

import net.runelite.client.config.*;

@ConfigGroup("lizardtrapper")
public interface LizardTrapperConfig extends Config
{
    @ConfigItem(
            keyName = "useNorth",
            name = "Use North Trap",
            description = "Enable/disable the North trap (3536, 3451, 0)"
    )
    default boolean useNorth() { return true; }

    @ConfigItem(
            keyName = "useSouth",
            name = "Use South Trap",
            description = "Enable/disable the South trap (3538, 3445, 0)"
    )
    default boolean useSouth() { return true; }

    @ConfigItem(
            keyName = "useNorthEast",
            name = "Use NorthEast Trap",
            description = "Enable/disable the NorthEast trap (3532, 3446, 0)"
    )
    default boolean useNorthEast() { return true; }

    @ConfigItem(
            keyName = "useSouthWest",
            name = "Use SouthWest Trap",
            description = "Enable/disable the SouthWest trap (3549, 3449, 0)"
    )
    default boolean useSouthWest() { return true; }

    @ConfigItem(
            keyName = "autowalkOnEnable",
            name = "Walk to Start on Enable",
            description = "If not at start tile, walk to 3536,3451,0 before starting."
    )
    default boolean autowalkOnEnable() { return true; }

    @Range(min = 0, max = 10_000)
    @ConfigItem(
            keyName = "pricePerLizard",
            name = "Price per Swamp Lizard (gp)",
            description = "Used for profit tracking when GE price is unavailable."
    )
    default int pricePerLizard() { return 1200; }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show Overlay",
            description = "Shows runtime, status, lizards banked, profit, XP/hr, and GE price if available."
    )
    default boolean showOverlay() { return true; }
}
