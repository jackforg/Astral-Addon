package dev.forg.commands;

import dev.forg.forg;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;

public class ForgVersionCommand extends Command {
    public ForgVersionCommand() {
        super("astralversion", "Prints Astral Addon by Forg version info.", "forgversion");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(this::run);
    }

    private int run(CommandContext<CommandSource> ctx) {
        ChatUtils.info("Astral Addon", "Astral Addon by Forg version: " + forg.VERSION);
        return 1;
    }
}
