package com.confia.schema;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A {@link DataSource} that remembers the text of every SQL statement prepared or executed through
 * the connections it hands out. A test that must prove a library "emitted no CREATE or ALTER" reads
 * the recorded text instead of trusting the library's documentation. Values bound to parameters are
 * never recorded, only the statement text.
 */
final class StatementRecordingDataSource extends DelegatingDataSource {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    StatementRecordingDataSource(DataSource target) {
        super(target);
    }

    /** Every statement text seen so far, in order. */
    List<String> statements() {
        return List.copyOf(statements);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return recording(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return recording(super.getConnection(username, password));
    }

    private Connection recording(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, new Recorder(connection, true));
    }

    private final class Recorder implements InvocationHandler {

        private final Object target;
        private final boolean connection;

        Recorder(Object target, boolean connection) {
            this.target = target;
            this.connection = connection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof String sql
                    && isStatementText(method.getName())) {
                statements.add(sql);
            }
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (connection && "createStatement".equals(method.getName())) {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                        new Class<?>[] {Statement.class}, new Recorder(result, false));
            }
            return result;
        }

        private boolean isStatementText(String methodName) {
            return methodName.startsWith("prepare") || "nativeSQL".equals(methodName)
                    || methodName.startsWith("execute") || "addBatch".equals(methodName);
        }
    }
}
