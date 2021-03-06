package org.sigmah.server.dao.util;

/*
 * #%L
 * Sigmah
 * %%
 * Copyright (C) 2010 - 2016 URD
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.sigmah.shared.dto.referential.DimensionType;

import org.sigmah.shared.util.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight DSL for building native SQL queries.
 */
public class SqlQueryBuilder {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(SqlQueryBuilder.class);

	protected StringBuilder fieldList = new StringBuilder();
	protected StringBuilder tableList = new StringBuilder();
	protected StringBuilder whereClause = new StringBuilder();
	protected StringBuilder orderByClause = new StringBuilder();
	protected String groupByClause;
	protected List<Object> parameters = new ArrayList<Object>();

	private String limitClause = "";

	public SqlQueryBuilder() {
	}

	/**
	 * Appends a table list to the {@code FROM} clause
	 * 
	 * @param fromClause
	 *          valid SQL table list, can include joins
	 */
	public SqlQueryBuilder from(String fromClause) {
		tableList.append(fromClause);
		return this;
	}

	/**
	 * Appends a left join to the {@code FROM} clause
	 * 
	 * @param tableName
	 * @return A new {@code JoinBuilder} instance.
	 */
	public JoinBuilder leftJoin(String tableName) {
		tableList.append(" LEFT JOIN ").append(tableName);
		return new JoinBuilder();
	}

	/**
	 * Appends a left join to derived table to the {@code FROM} clause
	 */
	public JoinBuilder leftJoin(SqlQueryBuilder derivedTable, String alias) {
		parameters.addAll(derivedTable.parameters);
		tableList.append(" LEFT JOIN (").append(derivedTable.sql()).append(")").append(" AS ").append(alias);

		return new JoinBuilder();
	}

	/**
	 * Appends a field or a comma separated list of fields to the field list.
	 */
	public SqlQueryBuilder appendField(String expr) {
		if (fieldList.length() != 0) {
			fieldList.append(", ");
		}
		fieldList.append(expr);
		return this;
	}

	public SqlQueryBuilder orderBy(String expr) {
		if (orderByClause.length() > 0) {
			orderByClause.append(", ");
		}
		orderByClause.append(expr);
		return this;
	}

	public void setLimitClause(String clause) {
		this.limitClause = clause;
	}

	public WhereClauseBuilder where(String expr) {
		if (whereClause.length() > 0) {
			whereClause.append(" AND ");
		}
		whereClause.append(expr);
		return new WhereClauseBuilder();
	}

	public SqlQueryBuilder whereTrue(String expr) {
		if (whereClause.length() > 0) {
			whereClause.append(" AND ");
		}
		whereClause.append(expr);
		return this;
	}

	public SqlQueryBuilder and(String expr) {
		whereClause.append(" AND (").append(expr).append(") ");
		return this;
	}

	public SqlQueryBuilder filteredBy(Filter filter) {
		for (DimensionType type : filter.getRestrictedDimensions()) {
			if (type == DimensionType.Indicator) {
				addIndicatorFilter(filter, type);

			} else if (type == DimensionType.Activity) {
				where("Site.ActivityId").in(filter.getRestrictions(type));

			} else if (type == DimensionType.Database) {
				where("Site.DatabaseId").in(filter.getRestrictions(type));

			} else if (type == DimensionType.Partner) {
				where("Site.PartnerId").in(filter.getRestrictions(type));

			} else if (type == DimensionType.AdminLevel) {
				where("Site.LocationId").in(select("Link.LocationId").from("LocationAdminLink Link").where("Link.AdminEntityId").in(filter.getRestrictions(type)));

			} else if (type == DimensionType.Site) {
				where("Site.SiteId").in(filter.getRestrictions(type));
			}
		}
		return this;
	}

	protected void addIndicatorFilter(Filter filter, DimensionType type) {
		where("Indicator.IndicatorId").in(filter.getRestrictions(type));
	}

	public SqlQueryBuilder groupBy(String string) {
		this.groupByClause = string;
		return this;
	}

	public String sql() {
		StringBuilder sql = new StringBuilder("SELECT ").append(fieldList).append(" FROM ").append(tableList);

		if (whereClause.length() > 0) {
			sql.append(" WHERE ").append(whereClause);
		}
		if (groupByClause != null) {
			sql.append(" GROUP BY ").append(groupByClause);
		}
		if (orderByClause.length() > 0) {
			sql.append(" ORDER BY ").append(orderByClause);
		}
		sql.append(" ").append(limitClause);

		return sql.toString();
	}

	public ResultSet executeQuery(Connection connection) throws SQLException {
		String sql = sql();
		LOGGER.debug(sql);
		PreparedStatement stmt = prepareStatement(connection, sql);
		return stmt.executeQuery();
	}

	private PreparedStatement prepareStatement(Connection connection, String sql) throws SQLException {
		PreparedStatement stmt = connection.prepareStatement(sql);
		for (int i = 0; i != parameters.size(); ++i) {
			stmt.setObject(i + 1, parameters.get(i));
		}
		return stmt;
	}

	public void forEachResult(Connection connection, ResultHandler handler) {
		PreparedStatement stmt = null;
		ResultSet rs = null;
		String sql = sql();

		try {
			stmt = prepareStatement(connection, sql);
			rs = stmt.executeQuery();

			handler.init(rs);

			while (rs.next()) {
				handler.handle(rs);
			}
		} catch (SQLException e) {
			throw new RuntimeException("Exception thrown while processing SQL: '" + sql + "'", e);
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException ignored) {
				}
			}
			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException ignored) {
				}
			}
		}
	}

	/**
	 * Executes the statement and returns the value of the first column of the first row, or null if there are no results.
	 * 
	 * @param conn
	 *          JDBC connection
	 */
	public Date singleDateResultOrNull(Connection conn) {
		PreparedStatement stmt = null;
		ResultSet rs = null;
		String sql = sql();

		try {
			stmt = prepareStatement(conn, sql);
			rs = stmt.executeQuery();

			if (rs.next()) {
				return rs.getDate(1);
			} else {
				return null;
			}
		} catch (SQLException e) {
			throw new RuntimeException("Exception thrown while processing SQL: '" + sql + "'", e);
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException ignored) {
				}
			}
			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException ignored) {
				}
			}
		}

	}

	public class WhereClauseBuilder {

		public SqlQueryBuilder in(Collection<?> ids) {
			if (ids.isEmpty()) {
				throw new IllegalArgumentException("Cannot match against empty list.");
			}
			if (ids.size() == 1) {
				whereClause.append(" = ?");
			} else {
				whereClause.append(" IN (? ");
				for (int i = 1; i < ids.size(); ++i) {
					whereClause.append(", ?");
				}
				whereClause.append(")");
			}
			parameters.addAll(ids);
			return SqlQueryBuilder.this;
		}

		public SqlQueryBuilder in(SqlQueryBuilder subquery) {
			whereClause.append(" IN (").append(subquery.sql()).append(") ");

			parameters.addAll(subquery.parameters);

			return SqlQueryBuilder.this;
		}

		public SqlQueryBuilder equalTo(Object value) {
			whereClause.append(" = ? ");
			parameters.add(value);

			return SqlQueryBuilder.this;
		}
	}

	public class JoinBuilder {

		public SqlQueryBuilder on(String expr) {
			tableList.append(" ON (").append(expr).append(") ");
			return SqlQueryBuilder.this;
		}
	}

	public static SqlQueryBuilder select(String... fieldList) {
		SqlQueryBuilder builder = new SqlQueryBuilder();
		for (String e : fieldList) {
			builder.appendField(e);
		}
		return builder;
	}

	public static abstract class ResultHandler {

		public void init(ResultSet rs) throws SQLException {

		}

		public abstract void handle(ResultSet rs) throws SQLException;

	}

}
