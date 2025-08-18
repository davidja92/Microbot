package net.runelite.client.plugins.microbot.huntertrapper;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = "HunterTrapper",
        description = "Microbot swamp lizard trapping with banking, looting, world hop, and overlay",
        tags = {"microbot", "hunter", "trapping", "lizard"}
)
public class HunterTrapperPlugin extends Plugin
{
    @Inject private OverlayManager overlayManager;
    @Inject private HunterTrapperOverlay overlay;
    @Inject private HunterTrapperConfig config;

    private HunterTrapperScript script;

    @Provides
    HunterTrapperConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(HunterTrapperConfig.class);
    }

    @Override
    protected void startUp()
    {
        script = new HunterTrapperScript();
        overlay.setScript(script);
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(overlay);
        if (script != null) script.shutdown();
        script = null;
    }
}
