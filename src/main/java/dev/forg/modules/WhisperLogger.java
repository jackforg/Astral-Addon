package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.ForgPaths;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhisperLogger extends Module {
    private static final Pattern INCOMING_PATTERN = Pattern.compile("^([^ ]+) whispers: (.+)$");
    private static final Pattern OUTGOING_PATTERN = Pattern.compile("^to ([^:]+): (.+)$");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> incoming = sgGeneral.add(new BoolSetting.Builder()
        .name("incoming")
        .description("Log incoming whispers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> outgoing = sgGeneral.add(new BoolSetting.Builder()
        .name("outgoing")
        .description("Log outgoing whispers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Save whispers to meteor-client/astral/whispers.csv.")
        .defaultValue(true)
        .build()
    );

    private File logFile;

    public WhisperLogger() {
        super(forg.UTILITY, "whisper-logger", "Logs incoming and outgoing whispers to a local file.");
    }

    @Override
    public void onActivate() {
        logFile = new File(ForgPaths.ensureDataDir(), "whispers.csv");
        if (logToFile.get() && !logFile.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write("timestamp,dimension,direction,player,message\n");
            } catch (IOException e) {
                error("Failed to initialize whisper log: " + e.getMessage());
            }
        }
    }

    @EventHandler(priority = 999)
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.world == null) return;

        String message = event.getMessage().getString();
        Matcher incomingMatcher = INCOMING_PATTERN.matcher(message);
        Matcher outgoingMatcher = OUTGOING_PATTERN.matcher(message);

        if (incoming.get() && incomingMatcher.matches()) {
            appendLog("incoming", incomingMatcher.group(1), incomingMatcher.group(2));
            DiscordNotifs.pushModuleEvent("WhisperLogger", "Incoming whisper from " + incomingMatcher.group(1) + ": " + incomingMatcher.group(2));
            return;
        }

        if (outgoing.get() && outgoingMatcher.matches()) {
            appendLog("outgoing", outgoingMatcher.group(1), outgoingMatcher.group(2));
            DiscordNotifs.pushModuleEvent("WhisperLogger", "Outgoing whisper to " + outgoingMatcher.group(1) + ": " + outgoingMatcher.group(2));
        }
    }

    private void appendLog(String direction, String player, String message) {
        if (!logToFile.get() || logFile == null) return;

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String dimension = mc.world == null ? "unknown" : mc.world.getRegistryKey().getValue().toString();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(String.format("%s,%s,%s,%s,%s%n",
                csvEscape(timestamp),
                csvEscape(dimension),
                csvEscape(direction),
                csvEscape(player),
                csvEscape(message)
            ));
        } catch (IOException e) {
            error("Failed to write whisper log: " + e.getMessage());
        }
    }

    private String csvEscape(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
