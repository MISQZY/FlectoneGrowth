package org.misqzy.flectonegrowth.common.storage;

import java.util.logging.Logger;

public final class MariaDbStorage extends AbstractSqlStorage {

    private static final String DEFAULT_PARAMS = "useSSL=false&serverTimezone=UTC";

    public MariaDbStorage(Logger logger, SqlConnectionSettings settings, String pluginVersion) {
        super(logger, settings, pluginVersion);
    }

    @Override
    protected String buildJdbcUrl(SqlConnectionSettings settings) {
        String params = settings.params().isBlank() ? DEFAULT_PARAMS : settings.params();
        return "jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.database() + "?" + params;
    }

    @Override protected String driverClassName() { return "org.mariadb.jdbc.Driver"; }
    @Override protected String storageName() { return "MariaDB"; }
    @Override public StorageType type() { return StorageType.MARIADB; }
}
