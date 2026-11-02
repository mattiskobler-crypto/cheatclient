package com.example.cheatclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/** Klick-Menue: oeffnen mit /cc gui */
public class CCScreen extends Screen {
    private EditBox schemBox;

    public CCScreen() {
        super(Component.literal("Cheat Client"));
    }

    private static Component label(String m) {
        boolean on = CheatClient.ON.contains(m);
        return Component.literal(m + ": " + (on ? "AN" : "AUS"))
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    @Override
    protected void init() {
        int cw = 150, gap = 6, step = 24;
        int lx = this.width / 2 - cw - gap / 2;
        int rx = this.width / 2 + gap / 2;
        int y = 20;

        // Module (2 Spalten)
        for (int i = 0; i < CheatClient.MODULES.size(); i++) {
            String m = CheatClient.MODULES.get(i);
            int x = (i % 2 == 0) ? lx : rx;
            int yy = y + (i / 2) * step;
            this.addRenderableWidget(Button.builder(label(m), btn -> {
                CheatClient.toggle(m);
                btn.setMessage(label(m));
            }).bounds(x, yy, cw, 20).build());
        }
        y += ((CheatClient.MODULES.size() + 1) / 2) * step;

        // Regler
        this.addRenderableWidget(new ValueSlider(lx, y, cw, 20, "flyspeed", 0.05, 1.0, CheatClient.flySpeed,
            v -> CheatClient.flySpeed = (float) v));
        this.addRenderableWidget(new ValueSlider(rx, y, cw, 20, "speed", 0.2, 2.0, CheatClient.maxSpeed,
            v -> CheatClient.maxSpeed = v));
        y += step;
        this.addRenderableWidget(new ValueSlider(lx, y, cw, 20, "camspeed", 0.2, 4.0, CheatClient.camSpeed,
            v -> CheatClient.camSpeed = v));
        this.addRenderableWidget(new ValueSlider(rx, y, cw, 20, "buildspeed", 10, 1000, CheatClient.buildPerTick,
            v -> CheatClient.buildPerTick = (int) v));
        y += step;

        // Aktionen
        this.addRenderableWidget(Button.builder(Component.literal("Sus-Chunks suchen"), btn -> {
            this.onClose();
            CheatClient.scanSus(CheatClient::chat, 5);
        }).bounds(lx, y, cw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Bau stoppen"), btn -> {
            CheatClient.QUEUE.clear();
        }).bounds(rx, y, cw, 20).build());
        y += step;

        // Schematic-Name + Bauen
        this.schemBox = new EditBox(this.font, lx, y, cw * 2 + gap, 20, Component.literal("Schematic"));
        this.schemBox.setHint(Component.literal("Schematic-Name (.schem)"));
        this.schemBox.setMaxLength(100);
        this.schemBox.setValue(CheatClient.lastSchem);
        this.addRenderableWidget(this.schemBox);
        y += step;

        this.addRenderableWidget(Button.builder(Component.literal("Bauen"), btn -> {
            String name = this.schemBox.getValue().trim();
            if (name.isEmpty()) return;
            CheatClient.lastSchem = name;
            this.onClose();
            CheatClient.startBuild(CheatClient::chat, name);
        }).bounds(lx, y, cw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Schliessen"), btn -> this.onClose())
            .bounds(rx, y, cw, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Singleplayer-Server soll weiterlaufen (Builder)
    }

    private static class ValueSlider extends AbstractSliderButton {
        private final String name;
        private final double min, max;
        private final DoubleConsumer setter;

        ValueSlider(int x, int y, int w, int h, String name, double min, double max, double cur, DoubleConsumer setter) {
            super(x, y, w, h, Component.empty(), Math.max(0, Math.min(1, (cur - min) / (max - min))));
            this.name = name;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(name + ": " + String.format("%.2f", min + (max - min) * this.value)));
        }

        @Override
        protected void applyValue() {
            setter.accept(min + (max - min) * this.value);
        }
    }
}
