package com.example.cheatclient;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Befehle:
 *  /cc <fly|speed|nightvision|sprint|esp|freecam>   Module an/aus
 *  /cc sus [min]            Chunks mit vielen Block-Entities (Kisten, Ofen, ...) suchen
 *  /cc schems               .schem-Dateien auflisten
 *  /cc build <name>         Schematic (.schem) an deiner Position bauen (nur Singleplayer)
 *  /cc build stop           Bau abbrechen
 *  /cc set flyspeed|speed|camspeed|buildspeed <wert>
 *  /cc gui                  Klick-Menue oeffnen
 *  /cc list, /cc off
 */
public class CheatClient implements ClientModInitializer {
    static final List<String> MODULES = List.of("fly", "speed", "nightvision", "sprint", "esp", "freecam");
    static final Set<String> ON = new TreeSet<>();
    static final Path SCHEM_DIR = FabricLoader.getInstance().getGameDir().resolve("schematics");

    static float flySpeed = 0.1f;
    static double maxSpeed = 0.6;
    static double camSpeed = 0.8;
    static int buildPerTick = 100;
    static boolean wasFly, wasEsp, wasNv;
    static boolean openGui;
    static String lastSchem = "";

    static boolean toggle(String m) {
        boolean now = !ON.remove(m);
        if (now) ON.add(m);
        return now;
    }

    static void chat(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(c, false);
    }

    // Freecam
    static RemotePlayer cam;
    static Vec3 camEye, frozenPos;

    // Builder
    record Pending(BlockPos pos, BlockState state) {}
    static final Deque<Pending> QUEUE = new ArrayDeque<>();
    static int buildTotal;

    @Override
    public void onInitializeClient() {
        try { Files.createDirectories(SCHEM_DIR); } catch (Exception ignored) {}

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("cc");

            for (String m : MODULES) {
                root.then(ClientCommandManager.literal(m).executes(ctx -> {
                    boolean now = toggle(m);
                    ctx.getSource().sendFeedback(Component.literal("[CC] " + m + (now ? " AN" : " AUS")));
                    return 1;
                }));
            }
            root.then(ClientCommandManager.literal("gui").executes(ctx -> {
                openGui = true;
                return 1;
            }));
            root.then(ClientCommandManager.literal("list").executes(ctx -> {
                ctx.getSource().sendFeedback(Component.literal("[CC] Module: " + MODULES + " | aktiv: " + ON));
                return 1;
            }));
            root.then(ClientCommandManager.literal("off").executes(ctx -> {
                ON.clear();
                QUEUE.clear();
                ctx.getSource().sendFeedback(Component.literal("[CC] alles aus"));
                return 1;
            }));

            root.then(ClientCommandManager.literal("sus")
                .executes(ctx -> { scanSus(c -> ctx.getSource().sendFeedback(c), 5); return 1; })
                .then(ClientCommandManager.argument("min", IntegerArgumentType.integer(1, 500)).executes(ctx -> {
                    scanSus(c -> ctx.getSource().sendFeedback(c), IntegerArgumentType.getInteger(ctx, "min"));
                    return 1;
                })));

            root.then(ClientCommandManager.literal("schems").executes(ctx -> {
                try (Stream<Path> s = Files.list(SCHEM_DIR)) {
                    List<String> names = s.map(x -> x.getFileName().toString()).filter(n -> n.endsWith(".schem")).sorted().toList();
                    ctx.getSource().sendFeedback(Component.literal("[CC] Ordner: " + SCHEM_DIR + " | Dateien: " + names));
                } catch (Exception e) {
                    ctx.getSource().sendError(Component.literal("[CC] Fehler: " + e));
                }
                return 1;
            }));

            root.then(ClientCommandManager.literal("build")
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    QUEUE.clear();
                    ctx.getSource().sendFeedback(Component.literal("[CC] Bau gestoppt"));
                    return 1;
                }))
                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                    startBuild(c -> ctx.getSource().sendFeedback(c), StringArgumentType.getString(ctx, "name").trim());
                    return 1;
                })));

            root.then(ClientCommandManager.literal("set")
                .then(setFloat("flyspeed", 0.01f, 2f, v -> flySpeed = v))
                .then(setFloat("speed", 0.1f, 3f, v -> maxSpeed = v))
                .then(setFloat("camspeed", 0.05f, 5f, v -> camSpeed = v))
                .then(setFloat("buildspeed", 1f, 2000f, v -> buildPerTick = (int) v)));

            dispatcher.register(root);
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) {
                cam = null;
                QUEUE.clear();
                return;
            }

            if (openGui) {
                openGui = false;
                mc.setScreen(new CCScreen());
            }

            // FLY
            if (ON.contains("fly")) {
                p.getAbilities().mayfly = true;
                p.getAbilities().setFlyingSpeed(flySpeed);
                wasFly = true;
            } else if (wasFly) {
                p.getAbilities().setFlyingSpeed(0.05f);
                if (!p.isCreative() && !p.isSpectator()) {
                    p.getAbilities().mayfly = false;
                    p.getAbilities().flying = false;
                }
                wasFly = false;
            }

            // SPEED
            if (ON.contains("speed")) {
                Vec3 v = p.getDeltaMovement();
                double s = Math.hypot(v.x, v.z);
                if (s > 0.05 && s < maxSpeed) {
                    double f = Math.min(maxSpeed / s, 1.1);
                    p.setDeltaMovement(v.x * f, v.y, v.z * f);
                }
            }

            // NACHTSICHT
            if (ON.contains("nightvision")) {
                p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1000, 0, false, false));
                wasNv = true;
            } else if (wasNv) {
                p.removeEffect(MobEffects.NIGHT_VISION);
                wasNv = false;
            }

            // SPRINT
            if (ON.contains("sprint") && mc.options.keyUp.isDown()) {
                mc.options.keySprint.setDown(true);
            }

            // ESP (Leuchten durch Waende)
            if (ON.contains("esp")) {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e != p && e instanceof LivingEntity) e.setGlowingTag(true);
                }
                wasEsp = true;
            } else if (wasEsp) {
                for (Entity e : mc.level.entitiesForRendering()) e.setGlowingTag(false);
                wasEsp = false;
            }

            // FREECAM
            if (ON.contains("freecam")) {
                if (cam == null) {
                    cam = new RemotePlayer(mc.level, p.getGameProfile());
                    camEye = p.getEyePosition();
                    frozenPos = p.position();
                    mc.setCameraEntity(cam);
                }
                if (mc.getCameraEntity() != cam) mc.setCameraEntity(cam);

                double yaw = Math.toRadians(p.getYRot());
                Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
                double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
                double r = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
                double u = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);
                Vec3 move = fwd.scale(f).add(right.scale(r)).add(0, u, 0);
                if (move.lengthSqr() > 0) move = move.normalize().scale(camSpeed);

                cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ();
                cam.yRotO = cam.getYRot(); cam.xRotO = cam.getXRot();
                camEye = camEye.add(move);
                cam.setPos(camEye.x, camEye.y - p.getEyeHeight(), camEye.z);
                cam.setYRot(p.getYRot());
                cam.setXRot(p.getXRot());

                // echten Spieler festhalten
                p.setPos(frozenPos.x, frozenPos.y, frozenPos.z);
                p.setDeltaMovement(Vec3.ZERO);
            } else if (cam != null) {
                mc.setCameraEntity(p);
                cam = null;
            }

            // SCHEMATIC-BUILDER
            if (!QUEUE.isEmpty()) {
                MinecraftServer server = mc.getSingleplayerServer();
                ServerLevel sl = server == null ? null : server.getLevel(mc.level.dimension());
                if (sl == null) {
                    QUEUE.clear();
                } else {
                    List<Pending> batch = new ArrayList<>();
                    for (int i = 0; i < buildPerTick && !QUEUE.isEmpty(); i++) batch.add(QUEUE.poll());
                    server.execute(() -> {
                        for (Pending b : batch) sl.setBlock(b.pos(), b.state(), 3);
                    });
                    if (QUEUE.isEmpty()) {
                        p.displayClientMessage(Component.literal("[CC] Bau fertig (" + buildTotal + " Bloecke)"), false);
                    }
                }
            }
        });
    }

    interface FloatSetter { void set(float v); }

    static LiteralArgumentBuilder<FabricClientCommandSource> setFloat(String name, float min, float max, FloatSetter s) {
        return ClientCommandManager.literal(name)
            .then(ClientCommandManager.argument("wert", FloatArgumentType.floatArg(min, max)).executes(ctx -> {
                float v = FloatArgumentType.getFloat(ctx, "wert");
                s.set(v);
                ctx.getSource().sendFeedback(Component.literal("[CC] " + name + " = " + v));
                return 1;
            }));
    }

    // ---------- SUS CHUNK FINDER ----------
    static void scanSus(Consumer<Component> out, int min) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        int r = mc.options.renderDistance().get();
        int pcx = p.blockPosition().getX() >> 4;
        int pcz = p.blockPosition().getZ() >> 4;
        List<int[]> found = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk c = mc.level.getChunk(pcx + dx, pcz + dz);
                int n = c.getBlockEntities().size();
                if (n >= min) found.add(new int[]{n, pcx + dx, pcz + dz});
            }
        }
        found.sort((a, b) -> Integer.compare(b[0], a[0]));
        if (found.isEmpty()) {
            out.accept(Component.literal("[CC] Keine verdaechtigen Chunks (min " + min + ", Radius " + r + ")"));
            return;
        }
        out.accept(Component.literal("[CC] Verdaechtige Chunks (Block-Entities: Kisten, Oefen, Trichter, ...):"));
        for (int i = 0; i < Math.min(8, found.size()); i++) {
            int[] f = found.get(i);
            out.accept(Component.literal(" " + f[0] + "x bei X " + (f[1] * 16 + 8) + "  Z " + (f[2] * 16 + 8)));
        }
    }

    // ---------- SCHEMATIC BUILDER (.schem, Sponge v2/v3) ----------
    static void startBuild(Consumer<Component> out, String name) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (mc.getSingleplayerServer() == null) {
            out.accept(Component.literal("[CC] Builder geht nur im Singleplayer"));
            return;
        }
        try {
            Path file = SCHEM_DIR.resolve(name.endsWith(".schem") ? name : name + ".schem");
            if (!Files.exists(file)) {
                out.accept(Component.literal("[CC] Nicht gefunden: " + file));
                return;
            }
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CompoundTag sch = root.getCompound("Schematic").orElse(root);
            int w = sch.getShortOr("Width", (short) 0) & 0xFFFF;
            int h = sch.getShortOr("Height", (short) 0) & 0xFFFF;
            int l = sch.getShortOr("Length", (short) 0) & 0xFFFF;

            CompoundTag paletteTag;
            byte[] data;
            if (sch.contains("Blocks")) {
                CompoundTag b = sch.getCompound("Blocks").orElseThrow();
                paletteTag = b.getCompound("Palette").orElseThrow();
                data = b.getByteArray("Data").orElseThrow();
            } else {
                paletteTag = sch.getCompound("Palette").orElseThrow();
                data = sch.getByteArray("BlockData").orElseThrow();
            }

            var lookup = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
            Map<Integer, BlockState> palette = new HashMap<>();
            for (String key : paletteTag.keySet()) {
                int id = paletteTag.getIntOr(key, 0);
                palette.put(id, BlockStateParser.parseForBlock(lookup, key, false).blockState());
            }

            BlockPos origin = p.blockPosition();
            QUEUE.clear();
            int i = 0;
            int expected = w * h * l;
            for (int idx = 0; idx < expected && i < data.length; idx++) {
                int value = 0, shift = 0, b;
                do {
                    b = data[i++];
                    value |= (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0 && i < data.length);
                BlockState st = palette.get(value);
                if (st == null || st.isAir()) continue;
                int x = idx % w;
                int z = (idx / w) % l;
                int y = idx / (w * l);
                QUEUE.add(new Pending(origin.offset(x, y, z), st));
            }
            buildTotal = QUEUE.size();
            out.accept(Component.literal("[CC] Baue " + buildTotal + " Bloecke (" + w + "x" + h + "x" + l + ") ab deiner Position"));
        } catch (Exception e) {
            out.accept(Component.literal("[CC] Fehler beim Laden: " + e));
        }
    }
}
