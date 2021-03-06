/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Florent Guillaume
 *     Benoit Delbosc
 */

package org.eclipse.ecr.core.storage.sql.jdbc.dialect;

import java.io.Serializable;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.ecr.core.NXCore;
import org.eclipse.ecr.core.api.security.SecurityConstants;
import org.eclipse.ecr.core.storage.StorageException;
import org.eclipse.ecr.core.storage.sql.BinaryManager;
import org.eclipse.ecr.core.storage.sql.ColumnType;
import org.eclipse.ecr.core.storage.sql.Model;
import org.eclipse.ecr.core.storage.sql.RepositoryDescriptor;
import org.eclipse.ecr.core.storage.sql.jdbc.db.Column;
import org.eclipse.ecr.core.storage.sql.jdbc.db.Database;
import org.eclipse.ecr.core.storage.sql.jdbc.db.Join;
import org.eclipse.ecr.core.storage.sql.jdbc.db.Table;
import org.nuxeo.common.utils.StringUtils;

/**
 * H2-specific dialect.
 *
 * @author Florent Guillaume
 */
public class DialectH2 extends Dialect {

    protected static final String DEFAULT_USERS_SEPARATOR = ",";

    private static final String DEFAULT_FULLTEXT_ANALYZER = "org.apache.lucene.analysis.standard.StandardAnalyzer";

    protected final String usersSeparator;

    public DialectH2(DatabaseMetaData metadata, BinaryManager binaryManager,
            RepositoryDescriptor repositoryDescriptor) throws StorageException {
        super(metadata, binaryManager, repositoryDescriptor);
        usersSeparator = repositoryDescriptor == null ? null
                : repositoryDescriptor.usersSeparatorKey == null ? DEFAULT_USERS_SEPARATOR
                        : repositoryDescriptor.usersSeparatorKey;
    }

    @Override
    public boolean supportsIfExistsAfterTableName() {
        return true;
    }

    @Override
    public JDBCInfo getJDBCTypeAndString(ColumnType type) {
        switch (type.spec) {
        case STRING:
            if (type.isUnconstrained()) {
                return jdbcInfo("VARCHAR", Types.VARCHAR);
            } else if (type.isClob()) {
                return jdbcInfo("CLOB", Types.CLOB);
            } else {
                return jdbcInfo("VARCHAR(%d)", type.length, Types.VARCHAR);
            }
        case BOOLEAN:
            return jdbcInfo("BOOLEAN", Types.BOOLEAN);
        case LONG:
            return jdbcInfo("BIGINT", Types.BIGINT);
        case DOUBLE:
            return jdbcInfo("DOUBLE", Types.DOUBLE);
        case TIMESTAMP:
            return jdbcInfo("TIMESTAMP", Types.TIMESTAMP);
        case BLOBID:
            return jdbcInfo("VARCHAR(40)", Types.VARCHAR);
            // -----
        case NODEID:
        case NODEIDFK:
        case NODEIDFKNP:
        case NODEIDFKMUL:
        case NODEIDFKNULL:
        case NODEIDPK:
        case NODEVAL:
            return jdbcInfo("VARCHAR(36)", Types.VARCHAR);
        case SYSNAME:
        case SYSNAMEARRAY:
            return jdbcInfo("VARCHAR(250)", Types.VARCHAR);
        case TINYINT:
            return jdbcInfo("TINYINT", Types.TINYINT);
        case INTEGER:
            return jdbcInfo("INTEGER", Types.INTEGER);
        case FTINDEXED:
            throw new AssertionError(type);
        case FTSTORED:
            return jdbcInfo("CLOB", Types.CLOB);
        case CLUSTERNODE:
            return jdbcInfo("INTEGER", Types.INTEGER);
        case CLUSTERFRAGS:
            return jdbcInfo("VARCHAR", Types.VARCHAR);
        }
        throw new AssertionError(type);
    }

    @Override
    public boolean isAllowedConversion(int expected, int actual,
            String actualName, int actualSize) {
        // CLOB vs VARCHAR compatibility
        if (expected == Types.VARCHAR && actual == Types.CLOB) {
            return true;
        }
        if (expected == Types.CLOB && actual == Types.VARCHAR) {
            return true;
        }
        // INTEGER vs BIGINT compatibility
        if (expected == Types.BIGINT && actual == Types.INTEGER) {
            return true;
        }
        if (expected == Types.INTEGER && actual == Types.BIGINT) {
            return true;
        }
        return false;
    }

    @Override
    public void setToPreparedStatement(PreparedStatement ps, int index,
            Serializable value, Column column) throws SQLException {
        switch (column.getJdbcType()) {
        case Types.VARCHAR:
        case Types.CLOB:
            setToPreparedStatementString(ps, index, value, column);
            return;
        case Types.BOOLEAN:
            ps.setBoolean(index, ((Boolean) value).booleanValue());
            return;
        case Types.TINYINT:
        case Types.INTEGER:
        case Types.BIGINT:
            ps.setLong(index, ((Long) value).longValue());
            return;
        case Types.DOUBLE:
            ps.setDouble(index, ((Double) value).doubleValue());
            return;
        case Types.TIMESTAMP:
            setToPreparedStatementTimestamp(ps, index, value, column);
            return;
        default:
            throw new SQLException("Unhandled JDBC type: "
                    + column.getJdbcType());
        }
    }

    @Override
    @SuppressWarnings("boxing")
    public Serializable getFromResultSet(ResultSet rs, int index, Column column)
            throws SQLException {
        switch (column.getJdbcType()) {
        case Types.VARCHAR:
        case Types.CLOB:
            return getFromResultSetString(rs, index, column);
        case Types.BOOLEAN:
            return rs.getBoolean(index);
        case Types.TINYINT:
        case Types.INTEGER:
        case Types.BIGINT:
            return rs.getLong(index);
        case Types.DOUBLE:
            return rs.getDouble(index);
        case Types.TIMESTAMP:
            return getFromResultSetTimestamp(rs, index, column);
        }
        throw new SQLException("Unhandled JDBC type: " + column.getJdbcType());
    }

    @Override
    public String getCreateFulltextIndexSql(String indexName,
            String quotedIndexName, Table table, List<Column> columns,
            Model model) {
        List<String> columnNames = new ArrayList<String>(columns.size());
        for (Column col : columns) {
            columnNames.add("'" + col.getPhysicalName() + "'");
        }
        String fullIndexName = String.format("PUBLIC_%s_%s",
                table.getPhysicalName(), indexName);
        String analyzer = model.getFulltextInfo().indexAnalyzer.get(indexName);
        if (analyzer == null) {
            analyzer = DEFAULT_FULLTEXT_ANALYZER;
        }
        return String.format(
                "CALL NXFT_CREATE_INDEX('%s', 'PUBLIC', '%s', (%s), '%s')",
                fullIndexName, table.getPhysicalName(),
                StringUtils.join(columnNames, ", "), analyzer);
    }

    @Override
    public String getDialectFulltextQuery(String query) {
        FulltextQuery ft = analyzeFulltextQuery(query);
        if (ft == null) {
            return "DONTMATCHANYTHINGFOREMPTYQUERY";
        }
        return translateFulltext(ft, "OR", "AND", "NOT", "\"");
    }

    // SELECT ..., 1 as nxscore
    // FROM ... LEFT JOIN NXFT_SEARCH('default', ?) nxfttbl
    // .................. ON hierarchy.id = nxfttbl.KEY
    // WHERE ... AND nxfttbl.KEY IS NOT NULL
    // ORDER BY nxscore DESC
    @Override
    public FulltextMatchInfo getFulltextScoredMatchInfo(String fulltextQuery,
            String indexName, int nthMatch, Column mainColumn, Model model,
            Database database) {
        String phftname = database.getTable(model.FULLTEXT_TABLE_NAME).getPhysicalName();
        String fullIndexName = "PUBLIC_" + phftname + "_" + indexName;
        String nthSuffix = nthMatch == 1 ? "" : String.valueOf(nthMatch);
        String tableAlias = "_nxfttbl" + nthSuffix;
        String scoreAlias = "_nxscore" + nthSuffix;
        // String scoreAlias = "_nxscore" + nthSuffix;
        FulltextMatchInfo info = new FulltextMatchInfo();
        info.joins = Collections.singletonList( //
        new Join(
                Join.LEFT, //
                String.format("NXFT_SEARCH('%s', ?)", fullIndexName),
                tableAlias, // alias
                fulltextQuery, // param
                String.format("%s.KEY", tableAlias), // on1
                mainColumn.getFullQuotedName() // on2
        ));
        info.whereExpr = String.format("%s.KEY IS NOT NULL", tableAlias);
        info.scoreExpr = String.format("1 AS %s", scoreAlias);
        info.scoreAlias = scoreAlias;
        info.scoreCol = new Column(mainColumn.getTable(), null,
                ColumnType.DOUBLE, null);
        return info;
    }

    @Override
    public boolean getMaterializeFulltextSyntheticColumn() {
        return false;
    }

    @Override
    public int getFulltextIndexedColumns() {
        return 2;
    }

    @Override
    public boolean supportsUpdateFrom() {
        return false; // check this, unused
    }

    @Override
    public boolean doesUpdateFromRepeatSelf() {
        return true;
    }

    @Override
    public String getClobCast(boolean inOrderBy) {
        if (!inOrderBy) {
            return "CAST(%s AS VARCHAR)";
        }
        return null;
    }

    @Override
    public String getSecurityCheckSql(String idColumnName) {
        return String.format("NX_ACCESS_ALLOWED(%s, ?, ?)", idColumnName);
    }

    @Override
    public String getInTreeSql(String idColumnName) {
        return String.format("NX_IN_TREE(%s, ?)", idColumnName);
    }

    @Override
    public boolean supportsArrays() {
        return false;
    }

    @Override
    public String getSQLStatementsFilename() {
        return "resources/nuxeovcs/h2.sql.txt";
    }

    @Override
    public String getTestSQLStatementsFilename() {
        return "resources/nuxeovcs/h2.test.sql.txt";
    }

    @Override
    public Map<String, Serializable> getSQLStatementsProperties(Model model,
            Database database) {
        Map<String, Serializable> properties = new HashMap<String, Serializable>();
        properties.put("idType", "VARCHAR(36)");
        String[] permissions = NXCore.getSecurityService().getPermissionsToCheck(
                SecurityConstants.BROWSE);
        List<String> permsList = new LinkedList<String>();
        for (String perm : permissions) {
            permsList.add("('" + perm + "')");
        }
        properties.put("fulltextEnabled", Boolean.valueOf(!fulltextDisabled));
        properties.put("readPermissions", StringUtils.join(permsList, ", "));
        properties.put("h2Functions",
                "org.eclipse.ecr.core.storage.sql.extensions.H2Functions");
        properties.put("h2Fulltext",
                "org.eclipse.ecr.core.storage.sql.extensions.H2Fulltext");
        properties.put("usersSeparator", getUsersSeparator());
        return properties;
    }

    @Override
    public boolean isClusteringSupported() {
        return true;
    }

    @Override
    public String getClusterInsertInvalidations() {
        return "CALL NX_CLUSTER_INVAL(?, ?, ?)";
    }

    @Override
    public String getClusterGetInvalidations() {
        return "SELECT * FROM NX_CLUSTER_GET_INVALS()";
    }

    @Override
    public boolean supportsPaging() {
        return true;
    }

    @Override
    public String getPagingClause(long limit, long offset) {
        return String.format("LIMIT %d OFFSET %d", limit, offset);
    }

    public String getUsersSeparator() {
        if (usersSeparator == null) {
            return DEFAULT_USERS_SEPARATOR;
        }
        return usersSeparator;
    }

}
