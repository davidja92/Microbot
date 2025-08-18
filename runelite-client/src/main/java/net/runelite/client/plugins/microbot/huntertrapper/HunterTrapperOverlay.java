package net.runelite.client.plugins.microbot.huntertrapper;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;

public class HunterTrapperOverlay extends OverlayPanel
{
    private final Client client;
    private HunterTrapperScript script;

    @Inject
    public HunterTrapperOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.TOP_LEFT);
        // Nice compact width
        panelComponent.setPreferredSize(new Dimension(210, 0));
    }

    /** Called by plugin at startup after it creates the script. */
    public void setScript(HunterTrapperScript script)
    {
        this.script = script;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("HunterTrapper")
                        .color(new Color(90, 220, 150))
                        .build()
        );

        if (script == null)
        {
            panelComponent.getChildren().add(LineComponent.builder().left("Status").right("Loading...").build());
            return super.render(g);
        }

        // Runtime
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Runtime")
                .right(formatDuration(script.getSessionRuntimeMs()))
                .build());

        // Catches
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Catches")
                .right(String.valueOf(script.getCatches()))
                .build());

        // XP/hr
        panelComponent.getChildren().add(LineComponent.builder()
                .left("XP/hr")
                .right(formatNumber(script.getXpPerHour()))
                .build());

        // Actions to next
        panelComponent.getChildren().add(LineComponent.builder()
                .left("To Lvl " + script.getNextLevelTarget())
                .right(formatNumber(script.getActionsToNext()) + " actions")
                .build());

        // Profit
        String each = script.getEachPrice() > 0 ? "@" + formatNumber(script.getEachPrice()) : "@—";
        panelComponent.getChildren().add(LineComponent.builder()
                .left("Profit")
                .right(formatNumber(script.getProfitGp()) + " gp " + each)
                .build());

        return super.render(g);
    }

    private static String formatDuration(long ms)
    {
        if (ms < 0) ms = 0;
        Duration d = Duration.ofMillis(ms);
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private static String formatNumber(long n)
    {
        if (n >= 1_000_000_000L) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000L)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
