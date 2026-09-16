package com.bovina.support.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** JDBC budget instrumentation; no suite parallelism or business scenario state is shared. */
@TestConfiguration(proxyBeanMethods = false)
public class ReadQueryCounting {
  @Bean
  static Counter readQueryCounter() {
    return new Counter();
  }

  public static final class Counter implements BeanPostProcessor {
    private final AtomicInteger prepared = new AtomicInteger();

    public void reset() {
      prepared.set(0);
    }

    public int count() {
      return prepared.get();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
      if (!(bean instanceof DataSource source) || !name.equals("dataSource")) return bean;
      return new DelegatingDataSource(source) {
        @Override
        public Connection getConnection() throws SQLException {
          return countQueries(super.getConnection());
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
          return countQueries(super.getConnection(user, password));
        }
      };
    }

    private Connection countQueries(Connection connection) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")) prepared.incrementAndGet();
                try {
                  return method.invoke(connection, args);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }
  }
}
