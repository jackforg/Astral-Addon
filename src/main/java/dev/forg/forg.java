package dev.forg;

import com.mojang.logging.LogUtils;
import dev.forg.commands.BannerBlacklist;
import dev.forg.commands.ChunkbaseCommand;
import dev.forg.commands.ForgVersionCommand;
import dev.forg.commands.GlowCommand;
import dev.forg.modules.*;
import dev.forg.modules.searcharea.SearchArea;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class forg extends MeteorAddon {
    public static final String VERSION = "3.5.1";

    public static final Logger LOG = LogUtils.getLogger();
    public static final Category WORLD = new Category("Astral World", Items.ENDER_CHEST.getDefaultStack());
    public static final Category UTILITY = new Category("Astral Utility", Items.NETHER_STAR.getDefaultStack());

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(WORLD);
        Modules.registerCategory(UTILITY);
    }

    @Override
    public void onInitialize() {
        LOG.info("Initializing Astral Addon {} by Forg for Minecraft 1.21.11", VERSION);

        // Original Astral modules.
        Modules.get().add(new ForgGlow());
        Modules.get().add(new AutoExtinguish());
        Modules.get().add(new ChestIndex());
        Modules.get().add(new PearlChecker());
        Modules.get().add(new SoundLocator());
        Modules.get().add(new OldChunkNotifier());
        Modules.get().add(new WhisperLogger());
        Modules.get().add(new automoss());
        Modules.get().add(new B36());
        Modules.get().add(new BannerFinder());
        Modules.get().add(new DeathExplore());
        Modules.get().add(new DropTest());
        Modules.get().add(new LawnMower());
        Modules.get().add(new MinecartDetector());
        Modules.get().add(new AutoIceBoat());
        Modules.get().add(new RocketSpeed());
        Modules.get().add(new TabGuiScale());

        // From stardust.
        Modules.get().add(new AdBlocker());
        Modules.get().add(new AutoDyeShulkers());
        Modules.get().add(new BookTools());
        Modules.get().add(new StashBrander());

        // From trouser-streak.
        Modules.get().add(new AdvancedItemESP());
        Modules.get().add(new MobGearESP());
        Modules.get().add(new PortalPatternFinder());

        // From jeff-mod.
        Modules.get().add(new AutoLogPlus());
        Modules.get().add(new ElytraFlyPlusPlus());
        Modules.get().add(new TrailFollower());
        Modules.get().add(new BetterStashFinder());
        Modules.get().add(new AutoPortal());
        Modules.get().add(new Pitch40Util());
        Modules.get().add(new TrailMaker());
        Modules.get().add(new DiscordNotifs());
        Modules.get().add(new AutoEXPPlus());
        Modules.get().add(new AFKVanillaFly());
        Modules.get().add(new GrimAirPlace());
        Modules.get().add(new SearchArea());

        // From numbyhack.
        Modules.get().add(new Beyblade());

        // From meteor-rejects.
        Modules.get().add(new AutoCraft());
        Modules.get().add(new AutoFarm());
        Modules.get().add(new AutoMine());
        Modules.get().add(new BlockIn());
        Modules.get().add(new CoordLogger());
        Modules.get().add(new Lavacast());
        Modules.get().add(new LootLocator());
        Modules.get().add(new OreSim());
        Modules.get().add(new Rendering());
        Modules.get().add(new SlimeChunks());

        // Commands.
        Commands.add(new BannerBlacklist());
        Commands.add(new ChunkbaseCommand());
        Commands.add(new ForgVersionCommand());
        Commands.add(new GlowCommand());

        LOG.info("Astral Addon {} initialized - {} modules loaded.", VERSION, Modules.get().getAll().size());
    }

    @Override
    public String getPackage() {
        return "dev.forg";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("jackforg", "Astral-Addon");
    }
}
