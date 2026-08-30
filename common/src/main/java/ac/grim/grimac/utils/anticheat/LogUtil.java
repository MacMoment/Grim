package ac.grim.grimac.utils.anticheat;

import ac.grim.grimac.GrimAPI;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;

import java.util.logging.Level;
import java.util.logging.Logger;

@UtilityClass
public class LogUtil {
    public void debug(final String message) {
        log(Level.FINE, message, null);
    }

    public void info(final String info) {
        log(Level.INFO, info, null);
    }

    public void warn(final String warn) {
        log(Level.WARNING, warn, null);
    }

    public void warn(final String description, final Throwable throwable) {
        log(Level.WARNING, description, throwable);
    }

    public void error(final String error) {
        log(Level.SEVERE, error, null);
    }

    public void error(final String description, final Throwable throwable) {
        log(Level.SEVERE, description, throwable);
    }

    public void error(final Throwable throwable) {
        log(Level.SEVERE, throwable.getMessage(), throwable);
    }

    public Logger getLogger() {
        return GrimAPI.INSTANCE.getGrimPlugin().getLogger();
    }

    public void console(final String info) {
        GrimAPI.INSTANCE.getPlatformServer().getConsoleSender().sendMessage(MessageUtil.translateAlternateColorCodes('&', info));
    }

    public void console(final Component info) {
        GrimAPI.INSTANCE.getPlatformServer().getConsoleSender().sendMessage(info);
    }

    private static void log(Level level, String message, Throwable throwable) {
        Logger logger = getLogger();
        if (throwable == null) {
            logger.log(level, message);
        } else {
            logger.log(level, message, throwable);
        }
    }
}
