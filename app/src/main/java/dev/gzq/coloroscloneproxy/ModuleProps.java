package dev.gzq.coloroscloneproxy;

final class ModuleProps {
    static final String PREFIX = "persist.clonevpnfix.";

    static final String ENABLED = PREFIX + "enabled";
    static final String MODE = PREFIX + "mode";
    static final String USERS = PREFIX + "users";
    static final String LOG = PREFIX + "log";

    static final String SETTING_ACTIVE = "clonevpnfix.active";
    static final String SETTING_STATE = "clonevpnfix.state";
    static final String SETTING_DETAIL = "clonevpnfix.detail";
    static final String SETTING_VERSION = "clonevpnfix.version";
    static final String SETTING_API = "clonevpnfix.api";
    static final String SETTING_FRAMEWORK = "clonevpnfix.framework";
    static final String SETTING_HOOKS = "clonevpnfix.hooks";
    static final String SETTING_EXPANDED_RANGES = "clonevpnfix.expanded_ranges";
    static final String SETTING_USERS = "clonevpnfix.users";
    static final String SETTING_BOOT_ID = "clonevpnfix.boot_id";
    static final String SETTING_LOG_SEQ = "clonevpnfix.log.seq";
    static final String SETTING_LOG_BOOT = "clonevpnfix.log.boot";
    static final String SETTING_LOG_PREFIX = "clonevpnfix.log.";

    static final String PROP_ACTIVE = "debug.clonevpnfix.active";
    static final String PROP_STATE = "debug.clonevpnfix.state";
    static final String PROP_DETAIL = "debug.clonevpnfix.detail";
    static final String PROP_VERSION = "debug.clonevpnfix.version";
    static final String PROP_API = "debug.clonevpnfix.api";
    static final String PROP_FRAMEWORK = "debug.clonevpnfix.framework";
    static final String PROP_HOOKS = "debug.clonevpnfix.hooks";
    static final String PROP_EXPANDED_RANGES = "debug.clonevpnfix.ranges";
    static final String PROP_USERS = "debug.clonevpnfix.users";
    static final String PROP_BOOT_ID = "debug.clonevpnfix.boot";

    static final String LOG_AUTHORITY = "dev.gzq.coloroscloneproxy.logs";
    static final String LOG_METHOD_APPEND = "append";
    static final String LOG_METHOD_IS_ENABLED = "isEnabled";
    static final String LOG_EXTRA_MESSAGE = "message";
    static final String LOG_EXTRA_ENABLED = "enabled";
    static final String LOG_EXTRA_OK = "ok";
    static final String LOG_FILE_NAME = "clone-vpn-fix.log";
    static final String LOG_PREFS = "log_settings";
    static final String LOG_ENABLED = "enabled";
    static final String LOG_LAST_SYNC_SEQ = "last_system_log_seq";
    static final String LOG_LAST_SYNC_BOOT = "last_system_log_boot";
    static final int LOG_FALLBACK_SIZE = 512;

    static final String DEFAULT_MODE = "non_owner";

    private ModuleProps() {
    }
}
