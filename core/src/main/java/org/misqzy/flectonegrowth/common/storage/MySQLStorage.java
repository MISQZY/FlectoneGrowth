package org.misqzy.flectonegrowth.common.storage;

import java.util.logging.Logger;

public final class MySQLStorage extends AbstractSqlStorage {

    private static final String DEFAULT_PARAMS = "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    public MySQLStorage(Logger logger, SqlConnectionSettings settings, String pluginVersion) {
        super(logger, settings, pluginVersion);
    }

    @Override
    protected String buildJdbcUrl(SqlConnectionSettings settings) {
        String params = settings.params().isBlank() ? DEFAULT_PARAMS : settings.params();
        return "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database() + "?" + params;
    }

    @Override protected String driverClassName() { return "com.mysql.cj.jdbc.Driver"; }
    @Override protected String storageName() { return "MySQL"; }
    @Override public StorageType type() { return StorageType.MYSQL; }
}
