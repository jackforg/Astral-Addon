package dev.forg.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.forg.utils.seed.SeedResolver;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.Dimension;
import net.minecraft.command.CommandSource;
import net.minecraft.util.Util;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ChunkbaseCommand extends Command {
    public ChunkbaseCommand() {
        super("chunkbase", "Copy or open a Chunkbase seed-map link for Astral's seed.", "cb");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> copyAndShow(resolveDimension("current")));

        builder.then(literal("copy")
            .executes(ctx -> copyAndShow(resolveDimension("current")))
            .then(argument("dimension", StringArgumentType.word())
                .executes(ctx -> copyAndShow(resolveDimension(StringArgumentType.getString(ctx, "dimension"))))
            )
        );

        builder.then(literal("open")
            .executes(ctx -> open(resolveDimension("current")))
            .then(argument("dimension", StringArgumentType.word())
                .executes(ctx -> open(resolveDimension(StringArgumentType.getString(ctx, "dimension"))))
            )
        );

        builder.then(argument("dimension", StringArgumentType.word())
            .executes(ctx -> copyAndShow(resolveDimension(StringArgumentType.getString(ctx, "dimension"))))
        );
    }

    private int copyAndShow(Dimension dimension) {
        if (dimension == null) return SINGLE_SUCCESS;

        String url = buildUrl(dimension);
        mc.keyboard.setClipboard(url);
        info("Copied Chunkbase URL for " + dimensionName(dimension) + " to clipboard.");
        info(url);
        return SINGLE_SUCCESS;
    }

    private int open(Dimension dimension) {
        if (dimension == null) return SINGLE_SUCCESS;

        String url = buildUrl(dimension);
        Util.getOperatingSystem().open(url);
        info("Opened Chunkbase for " + dimensionName(dimension) + ".");
        info(url);
        return SINGLE_SUCCESS;
    }

    private Dimension resolveDimension(String value) {
        return switch (value.toLowerCase()) {
            case "current" -> PlayerUtils.getDimension();
            case "overworld", "ow" -> Dimension.Overworld;
            case "nether" -> Dimension.Nether;
            case "end" -> Dimension.End;
            default -> {
                error("Unknown dimension '" + value + "'. Use current, overworld, nether, or end.");
                yield null;
            }
        };
    }

    private String buildUrl(Dimension dimension) {
        Long resolvedSeed = SeedResolver.resolve(mc, "");
        long seed = resolvedSeed != null ? resolvedSeed : Long.parseLong(SeedResolver.DEFAULT_MULTIPLAYER_SEED);
        int x = mc.player != null ? mc.player.getBlockX() : 0;
        int z = mc.player != null ? mc.player.getBlockZ() : 0;

        String dimensionId = switch (dimension) {
            case Nether -> "nether";
            case End -> "end";
            default -> "overworld";
        };

        return "https://www.chunkbase.com/apps/seed-map#seed=" + seed
            + "&platform=java_1_21_9"
            + "&dimension=" + dimensionId
            + "&x=" + x
            + "&z=" + z
            + "&zoom=0.75";
    }

    private String dimensionName(Dimension dimension) {
        return switch (dimension) {
            case Nether -> "Nether";
            case End -> "End";
            default -> "Overworld";
        };
    }
}
