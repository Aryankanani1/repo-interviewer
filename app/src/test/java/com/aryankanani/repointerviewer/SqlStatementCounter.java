package com.aryankanani.repointerviewer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Counts the SQL statements the application sends, for tests that prove a query count (task 1.13, N+1). It wraps
 * the {@link DataSource} bean, so it sees everything: Hibernate's queries and our {@code JdbcTemplate} ones alike.
 * Recording only happens between {@link #start} and {@link #stop}; otherwise it just passes calls through.
 *
 * <p>How: a {@link BeanPostProcessor} swaps the DataSource for a {@link DelegatingDataSource} whose connections are
 * Java dynamic proxies. A proxy implements an interface ({@link Connection}) by sending every method call to one
 * handler, which records the SQL of {@code prepareStatement}/{@code prepareCall} and of plain {@link Statement}
 * {@code execute} calls, then calls the real connection.
 */
public class SqlStatementCounter implements BeanPostProcessor {

	private static final Set<String> STATEMENT_EXECUTES = Set.of("execute", "executeQuery", "executeUpdate",
			"executeLargeUpdate", "addBatch");

	private final List<String> statements = new CopyOnWriteArrayList<>();

	private volatile boolean recording;

	public void start() {
		statements.clear();
		recording = true;
	}

	/** @return every statement since {@link #start}, in order */
	public List<String> stop() {
		recording = false;
		return List.copyOf(statements);
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)) {
			return new CountingDataSource(dataSource);
		}
		return bean;
	}

	private final class CountingDataSource extends DelegatingDataSource {

		CountingDataSource(DataSource target) {
			super(target);
		}

		@Override
		public Connection getConnection() throws SQLException {
			return counting(super.getConnection());
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return counting(super.getConnection(username, password));
		}

	}

	private Connection counting(Connection connection) {
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
				new Recorder(connection, true));
	}

	/** Records the SQL, then calls the real object. For a Connection it also wraps the Statements it creates. */
	private final class Recorder implements InvocationHandler {

		private final Object target;

		private final boolean connection;

		Recorder(Object target, boolean connection) {
			this.target = target;
			this.connection = connection;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();
			boolean hasSql = args != null && args.length > 0 && args[0] instanceof String;
			if (recording && hasSql && (connection ? name.startsWith("prepare") : STATEMENT_EXECUTES.contains(name))) {
				statements.add((String) args[0]);
			}
			Object result;
			try {
				result = method.invoke(target, args);
			}
			catch (InvocationTargetException ex) {
				throw ex.getCause();
			}
			if (connection && name.equals("createStatement") && result instanceof Statement statement) {
				return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] { Statement.class },
						new Recorder(statement, false));
			}
			return result;
		}

	}

}
