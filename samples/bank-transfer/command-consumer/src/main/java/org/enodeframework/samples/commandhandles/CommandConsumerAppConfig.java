package org.enodeframework.samples.commandhandles;

import com.google.common.collect.Lists;
import com.zaxxer.hikari.HikariDataSource;
import io.vertx.core.Vertx;
import org.enodeframework.ENodeBootstrap;
import org.enodeframework.commanding.impl.DefaultCommandProcessor;
import org.enodeframework.commanding.impl.DefaultProcessingCommandHandler;
import org.enodeframework.eventing.impl.DefaultEventCommittingService;
import org.enodeframework.mysql.MysqlEventStore;
import org.enodeframework.mysql.MysqlPublishedVersionStore;
import org.enodeframework.queue.command.CommandResultProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

import static org.enodeframework.samples.QueueProperties.JDBC_URL;

@Configuration
public class CommandConsumerAppConfig {
    private Vertx vertx;
    @Autowired
    private MysqlEventStore mysqlEventStore;
    @Autowired
    private MysqlPublishedVersionStore publishedVersionStore;
    @Autowired
    private CommandResultProcessor commandResultProcessor;

//    @Bean
//    public InMemoryEventStore inMemoryEventStore() {
//        return new InMemoryEventStore();
//    }
//
//    @Bean
//    public InMemoryPublishedVersionStore inMemoryPublishedVersionStore() {
//        return new InMemoryPublishedVersionStore();
//    }

    /**
     * 命令处理器
     */
    @Bean
    public DefaultProcessingCommandHandler defaultProcessingCommandHandler() {
        return new DefaultProcessingCommandHandler();
    }

    @Bean
    public DefaultEventCommittingService defaultEventService() {
        return new DefaultEventCommittingService();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public DefaultCommandProcessor defaultCommandProcessor() {
        return new DefaultCommandProcessor();
    }

    @Bean(initMethod = "init")
    public ENodeBootstrap eNodeBootstrap() {
        ENodeBootstrap bootstrap = new ENodeBootstrap();
        bootstrap.setScanPackages(Lists.newArrayList("org.enodeframework.samples"));
        return bootstrap;
    }

    @Bean
    public MysqlEventStore mysqlEventStore(HikariDataSource dataSource) {
        return new MysqlEventStore(dataSource, null);
    }

    @Bean
    public MysqlPublishedVersionStore mysqlPublishedVersionStore(HikariDataSource dataSource) {
        return new MysqlPublishedVersionStore(dataSource, null);
    }

    @Bean
    public HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(JDBC_URL);
        dataSource.setUsername("root");
        dataSource.setPassword("root");
        dataSource.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
        return dataSource;
    }

    @PostConstruct
    public void deployVerticle() {
        vertx = Vertx.vertx();
        vertx.deployVerticle(commandResultProcessor, res -> {

        });
        vertx.deployVerticle(mysqlEventStore, res -> {

        });
        vertx.deployVerticle(publishedVersionStore, res -> {

        });
    }
}
