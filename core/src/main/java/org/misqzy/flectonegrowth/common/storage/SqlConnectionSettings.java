package org.misqzy.flectonegrowth.common.storage;

/**
 * The subset of {@code storage.database.*} config.yml keys every JDBC-backed
 * {@link Storage} needs to open a HikariCP pool - grouped into one value so
 * {@link StorageFactory}, {@link AbstractSqlStorage}, {@link MySQLStorage}
 * and {@link MariaDbStorage} pass it around as a single argument instead of
 * ten-plus positional parameters that are easy to transpose (two {@code long}
 * timeouts in particular) without the compiler catching it.
 *
 * @param tablePrefix prepended to every table name this project reads/writes - see {@link TableNames}
 * @param params      raw JDBC URL query string appended after '?' (e.g. {@code "useSSL=true&characterEncoding=utf8"});
 *                    blank keeps each dialect's own built-in default - see {@code buildJdbcUrl} in
 *                    {@link MySQLStorage}/{@link MariaDbStorage}
 */
public record SqlConnectionSettings(
        String host,
        int port,
        String database,
        String username,
        String password,
        String tablePrefix,
        String params,
        int maxPoolSize,
        int minIdle,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs
) {}
