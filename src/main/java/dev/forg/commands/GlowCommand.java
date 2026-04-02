package dev.forg.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.forg.modules.ForgGlow;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.UUID;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class GlowCommand extends Command {
    public GlowCommand() {
        super("glow", "Manage Astral Glow's local list and sharing status.", "ag", "fg");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("add")
            .then(argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    if (mc.world == null) { error("Not in a world."); return SINGLE_SUCCESS; }
                    ForgGlow module = Modules.get().get(ForgGlow.class);
                    PlayerEntity target = findPlayer(name);
                    if (target == null) { error("Player '" + name + "' not found nearby."); return SINGLE_SUCCESS; }
                    boolean added = module.add(target.getUuid(), target.getGameProfile().name());
                    info(added ? "Added " + name + " to glow list." : name + " is already in the glow list.");
                    return SINGLE_SUCCESS;
                })
            )
            .executes(ctx -> { error("Usage: .glow add <player>"); return SINGLE_SUCCESS; })
        );

        builder.then(literal("remove")
            .then(argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    ForgGlow module = Modules.get().get(ForgGlow.class);
                    UUID uuid = findInList(module, name);
                    if (uuid == null && mc.world != null) {
                        PlayerEntity t = findPlayer(name);
                        if (t != null) uuid = t.getUuid();
                    }
                    if (uuid == null) { error("Player '" + name + "' not found in glow list."); return SINGLE_SUCCESS; }
                    module.remove(uuid);
                    info("Removed " + name + " from glow list.");
                    return SINGLE_SUCCESS;
                })
            )
            .executes(ctx -> { error("Usage: .glow remove <player>"); return SINGLE_SUCCESS; })
        );

        builder.then(literal("list")
            .executes(ctx -> {
                ForgGlow module = Modules.get().get(ForgGlow.class);
                Map<UUID, String> list = module.getGlowList();
                if (list.isEmpty()) {
                    info("Glow list is empty.");
                } else {
                    info("Glowing players (" + list.size() + "):");
                    list.forEach((uuid, name) -> info("  " + name + " (" + uuid + ")"));
                }
                return SINGLE_SUCCESS;
            })
        );

        builder.then(literal("status")
            .executes(ctx -> {
                ForgGlow module = Modules.get().get(ForgGlow.class);
                info("Glow status:");
                info("  Sharing enabled: " + module.isPresenceSharingEnabled());
                info("  Share URL: " + formatValue(module.getShareUrl()));
                info("  Public list URL: " + formatValue(module.getRemoteUrl()));
                info("  Remote shared users loaded: " + module.getRemoteListSize());
                info("  Last public list fetch: " + module.getLastFetchStatus());
                info("  Last presence share: " + module.getLastShareStatus());
                info("  " + module.getSharedFieldSummary());
                return SINGLE_SUCCESS;
            })
        );

        builder.then(literal("refresh")
            .executes(ctx -> {
                ForgGlow module = Modules.get().get(ForgGlow.class);
                module.refreshSharedRegistryNow();
                info("Requested a list refresh and share attempt.");
                return SINGLE_SUCCESS;
            })
        );

        builder.then(literal("clear")
            .executes(ctx -> {
                Modules.get().get(ForgGlow.class).clear();
                info("Glow list cleared.");
                return SINGLE_SUCCESS;
            })
        );
    }

    private PlayerEntity findPlayer(String name) {
        if (mc.world == null) return null;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private UUID findInList(ForgGlow module, String name) {
        for (Map.Entry<UUID, String> e : module.getGlowList().entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    private String formatValue(String value) {
        return value == null || value.isBlank() ? "(not set)" : value;
    }
}
