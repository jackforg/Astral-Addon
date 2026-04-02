package dev.forg.util;

import dev.forg.forg;

public class LogUtil {
    public static void info(String msg) {
        forg.LOG.info("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void info(String msg, String module) {
        forg.LOG.info("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
    public static void warn(String msg) {
        forg.LOG.warn("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void warn(String msg, String module) {
        forg.LOG.warn("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
    public static void error(String msg) {
        forg.LOG.error("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void error(String msg, String module) {
        forg.LOG.error("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
    public static void debug(String msg) {
        forg.LOG.debug("{} {}", MsgUtil.getRawPrefix(), msg);
    }
    public static void debug(String msg, String module) {
        forg.LOG.debug("{}{} {}", MsgUtil.getRawPrefix(), MsgUtil.getRawPrefix(module), msg);
    }
}
