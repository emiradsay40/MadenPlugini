package maden.tr.skyblock.maden;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class MadenDatabase {
    private final String jdbcUrl;

    public MadenDatabase(File dataFolder) {
        this.jdbcUrl = "jdbc:sqlite:" + new File(dataFolder, "tracked-blocks.db").getAbsolutePath();
    }

    public void initialize() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS tracked_blocks (" +
                "block_key TEXT PRIMARY KEY," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "mine_id TEXT NOT NULL," +
                "type_id TEXT NOT NULL," +
                "broken_until INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracked_blocks_mine_id ON tracked_blocks(mine_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracked_blocks_mine_type ON tracked_blocks(mine_id, type_id)");
        }
    }

    public List<MadenPlugin.TrackedBlockRecord> loadAll() throws Exception {
        List<MadenPlugin.TrackedBlockRecord> records = new ArrayList<MadenPlugin.TrackedBlockRecord>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT block_key, world, x, y, z, mine_id, type_id, broken_until FROM tracked_blocks"
             );
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(new MadenPlugin.TrackedBlockRecord(
                    resultSet.getString("block_key"),
                    resultSet.getString("world"),
                    resultSet.getInt("x"),
                    resultSet.getInt("y"),
                    resultSet.getInt("z"),
                    resultSet.getString("mine_id"),
                    resultSet.getString("type_id"),
                    resultSet.getLong("broken_until")
                ));
            }
        }
        return records;
    }

    public void replaceMineBlocks(String mineId, List<MadenPlugin.TrackedBlockRecord> records) throws Exception {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteStatement = connection.prepareStatement("DELETE FROM tracked_blocks WHERE mine_id = ?");
                 PreparedStatement insertStatement = connection.prepareStatement(
                     "INSERT OR REPLACE INTO tracked_blocks(block_key, world, x, y, z, mine_id, type_id, broken_until) VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
                 )) {
                deleteStatement.setString(1, mineId);
                deleteStatement.executeUpdate();

                for (MadenPlugin.TrackedBlockRecord record : records) {
                    insertStatement.setString(1, record.blockKey);
                    insertStatement.setString(2, record.worldName);
                    insertStatement.setInt(3, record.x);
                    insertStatement.setInt(4, record.y);
                    insertStatement.setInt(5, record.z);
                    insertStatement.setString(6, record.mineId);
                    insertStatement.setString(7, record.typeId);
                    insertStatement.setLong(8, record.brokenUntil);
                    insertStatement.addBatch();
                }
                insertStatement.executeBatch();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void upsert(MadenPlugin.TrackedBlockRecord record) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT OR REPLACE INTO tracked_blocks(block_key, world, x, y, z, mine_id, type_id, broken_until) VALUES(?, ?, ?, ?, ?, ?, ?, ?)"
             )) {
            statement.setString(1, record.blockKey);
            statement.setString(2, record.worldName);
            statement.setInt(3, record.x);
            statement.setInt(4, record.y);
            statement.setInt(5, record.z);
            statement.setString(6, record.mineId);
            statement.setString(7, record.typeId);
            statement.setLong(8, record.brokenUntil);
            statement.executeUpdate();
        }
    }

    public void deleteMine(String mineId) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM tracked_blocks WHERE mine_id = ?")) {
            statement.setString(1, mineId);
            statement.executeUpdate();
        }
    }

    public void deleteType(String mineId, String typeId) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM tracked_blocks WHERE mine_id = ? AND type_id = ?")) {
            statement.setString(1, mineId);
            statement.setString(2, typeId);
            statement.executeUpdate();
        }
    }

    private Connection openConnection() throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }
}
