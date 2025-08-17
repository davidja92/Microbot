package net.runelite.client.plugins.microbot.lizardtrapper;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.text.NumberFormat;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class LizardTrapperOverlay extends Overlay
{
    private final PanelComponent panel = new PanelComponent();

    private LizardTrapperScript script;
    private LizardTrapperConfig config;

    @Inject
    public LizardTrapperOverlay()
    {
        setPosition(OverlayPosition.TOP_LEFT);
    }

    public void setScript(LizardTrapperScript script) { this.script = script; }
    public void setConfig(LizardTrapperConfig config) { this.config = config; }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || script == null || !config.showOverlay())
            return null;

        panel.getChildren().clear();

        final NumberFormat nf = NumberFormat.getInstance();

        panel.getChildren().add(TitleComponent.builder()
                .text("Lizard Trapper")
                .build());

        panel.getChildren().add(LineComponent.builder()
                .left("Status")
                .right(script.statusLine())
                .build());

        panel.getChildren().add(LineComponent.builder()
                .left("Runtime")
                .right(script.getFormattedUptime())
                .build());

        // Prices
        Integer gePrice = script.getGePriceSwampLizard();
        panel.getChildren().add(LineComponent.builder()
                .left("Config Price")
                .right(nf.format(config.pricePerLizard()))
                .build());
        panel.getChildren().add(LineComponent.builder()
                .left("GE Price")
                .right(gePrice == null ? "n/a" : nf.format(gePrice))
                .build());

        // Lizards & Profit
        panel.getChildren().add(LineComponent.builder()
                .left("Lizards Banked")
                .right(Long.toString(script.getTotalLizBanked()))
                .build());

        panel.getChildren().add(LineComponent.builder()
                .left("Profit (Config)")
                .right(nf.format(script.getTotalProfit(config.pricePerLizard())))
                .build());

        panel.getChildren().add(LineComponent.builder()
                .left("Profit (GE)")
                .right(gePrice == null ? "n/a" : nf.format(script.getTotalProfit(gePrice)))
                .build());

        // XP
        panel.getChildren().add(LineComponent.builder()
                .left("XP Gained")
                .right(nf.format(script.getXpGained()))
                .build());
        panel.getChildren().add(LineComponent.builder()
                .left("XP / hr")
                .right(nf.format(script.getXpPerHour()))
                .build());

        return panel.render(g);
    }
}
