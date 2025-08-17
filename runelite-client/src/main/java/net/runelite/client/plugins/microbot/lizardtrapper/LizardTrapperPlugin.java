package net.runelite.client.plugins.microbot.lizardtrapper;

import com.google.inject.Provides;
import javax.inject.Inject;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.plugins.microbot.Microbot;

@PluginDescriptor(
        name = "Microbot - Lizard Trapper",
        description = "Automates Swamp Lizard catching with banking, XP & profit tracking.",
        tags = {"microbot","hunter","lizard","trap","automation"},
        enabledByDefault = false   // <-- disabled at client start; user must enable first
)
public class LizardTrapperPlugin extends Plugin
{
    @Inject private LizardTrapperConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private LizardTrapperOverlay overlay;

    // Script instance shared with overlay
    private final LizardTrapperScript script = new LizardTrapperScript();

    @Provides
    LizardTrapperConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LizardTrapperConfig.class);
    }

    @Override
    protected void startUp()
    {
        // Runs ONLY when you enable the plugin in RuneLite
        overlay.setScript(script);
        overlay.setConfig(config);
        overlayManager.add(overlay);

        script.run(config);
        Microbot.log("Lizard Trapper started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        script.shutdown();
        Microbot.log("Lizard Trapper stopped");
    }
}
