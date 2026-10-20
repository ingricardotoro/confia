package com.confia.identity.application;

import com.confia.shared.security.TransactionRunner;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The real {@link TransactionRunner} over a JDBC stub that does nothing but remember {@code commit}
 * and {@code rollback}, for the unit tests of the use cases that open a transaction (testing
 * playbook, section 1). That is how a test tells a rejection that is committed, because it is a
 * returned value, from a failure that is rolled back.
 */
public final class JdbcStubTransactions {

    private JdbcStubTransactions() {
    }

    /**
     * Every JDBC call does nothing and returns the default of its type, except that {@code commit}
     * and {@code rollback} are added to {@code calls}.
     */
    public static TransactionRunner runner(List<String> calls) {
        return runner(calls, new java.util.ArrayList<>());
    }

    /**
     * As above, and every value the transaction passes to {@code setReadOnly} is added, as text, to
     * {@code readOnlyFlags}: how a unit test tells a read-only transaction from a read-write one.
     */
    public static TransactionRunner runner(List<String> calls, List<String> readOnlyFlags) {
        PreparedStatement statement = stub(PreparedStatement.class, (proxy, method, args) -> null);
        Connection connection = stub(Connection.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "commit", "rollback" -> calls.add(method.getName());
                case "setReadOnly" -> readOnlyFlags.add(String.valueOf(args[0]));
                case "prepareStatement" -> {
                    return statement;
                }
                default -> {
                }
            }
            return null;
        });
        DataSource dataSource = stub(DataSource.class, (proxy, method, args) ->
                method.getName().equals("getConnection") ? connection : null);
        return new TransactionRunner(new DataSourceTransactionManager(dataSource), dataSource);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        InvocationHandler withDefaults = (proxy, method, args) -> {
            Object result = handler.invoke(proxy, method, args);
            if (result != null || !method.getReturnType().isPrimitive()) {
                return result;
            }
            Class<?> primitive = method.getReturnType();
            if (primitive == boolean.class) {
                return false;
            }
            if (primitive == void.class) {
                return null;
            }
            return primitive == long.class ? 0L : primitive == int.class ? 0 : (Object) 0;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, withDefaults);
    }
}
