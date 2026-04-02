package dev.forg.utils.seed;

import dev.forg.util.OptionalModSupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class BaritoneBridge {
    private BaritoneBridge() {
    }

    public static boolean isAvailable() {
        return OptionalModSupport.BARITONE_AVAILABLE;
    }

    public static boolean pathToXZ(int x, int z) {
        if (!isAvailable()) return false;

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object primaryBaritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object customGoalProcess = primaryBaritone.getClass().getMethod("getCustomGoalProcess").invoke(primaryBaritone);

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalXZClass = Class.forName("baritone.api.pathing.goals.GoalXZ");
            Constructor<?> constructor = goalXZClass.getConstructor(int.class, int.class);
            Object goal = constructor.newInstance(x, z);

            Method setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass);
            setGoalAndPath.invoke(customGoalProcess, goal);
            return true;
        }
        catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static boolean cancel() {
        if (!isAvailable()) return false;

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object primaryBaritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object commandManager = primaryBaritone.getClass().getMethod("getCommandManager").invoke(primaryBaritone);
            commandManager.getClass().getMethod("execute", String.class).invoke(commandManager, "cancel");
            return true;
        }
        catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
