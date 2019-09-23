package org.enodeframework.infrastructure.impl;

import org.enodeframework.common.scheduling.IScheduleService;
import org.enodeframework.infrastructure.IMessage;
import org.enodeframework.infrastructure.IMessageProcessor;
import org.enodeframework.infrastructure.IProcessingMessage;
import org.enodeframework.infrastructure.IProcessingMessageHandler;
import org.enodeframework.infrastructure.IProcessingMessageScheduler;
import org.enodeframework.infrastructure.ProcessingMessageMailbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * @author anruence@gmail.com
 */
public class DefaultMessageProcessor<X extends IProcessingMessage<X, Y>, Y extends IMessage> implements IMessageProcessor<X, Y> {
    private static final Logger logger = LoggerFactory.getLogger(DefaultMessageProcessor.class);
    private int timeoutSeconds = 3600 * 24 * 3;
    private int scanExpiredAggregateIntervalMilliseconds = 5000;
    private String taskName;
    private ConcurrentMap<String, ProcessingMessageMailbox<X, Y>> mailboxDict;
    @Autowired
    private IScheduleService scheduleService;
    @Autowired
    private IProcessingMessageScheduler<X, Y> processingMessageScheduler;
    @Autowired
    private IProcessingMessageHandler<X, Y> processingMessageHandler;

    public DefaultMessageProcessor() {
        mailboxDict = new ConcurrentHashMap<>();
        taskName = "CleanInactiveAggregates" + System.nanoTime() + new Random().nextInt(10000);
    }

    public DefaultMessageProcessor<X, Y> setScheduleService(IScheduleService scheduleService) {
        this.scheduleService = scheduleService;
        return this;
    }

    public DefaultMessageProcessor<X, Y> setProcessingMessageScheduler(IProcessingMessageScheduler<X, Y> processingMessageScheduler) {
        this.processingMessageScheduler = processingMessageScheduler;
        return this;
    }

    public DefaultMessageProcessor<X, Y> setProcessingMessageHandler(IProcessingMessageHandler<X, Y> processingMessageHandler) {
        this.processingMessageHandler = processingMessageHandler;
        return this;
    }

    public String getMessageName() {
        return "message";
    }

    @Override
    public void process(X processingMessage) {
        String routingKey = processingMessage.getMessage().getRoutingKey();
        if (routingKey != null && !"".equals(routingKey.trim())) {
            ProcessingMessageMailbox<X, Y> mailbox = mailboxDict.computeIfAbsent(routingKey, key -> new ProcessingMessageMailbox<>(routingKey, processingMessageScheduler, processingMessageHandler));
            mailbox.enqueueMessage(processingMessage);
        } else {
            processingMessageScheduler.scheduleMessage(processingMessage);
        }
    }

    @Override
    public void start() {
        scheduleService.startTask(taskName, this::cleanInactiveMailbox, scanExpiredAggregateIntervalMilliseconds, scanExpiredAggregateIntervalMilliseconds);
    }

    @Override
    public void stop() {
        scheduleService.stopTask(taskName);
    }

    private void cleanInactiveMailbox() {
        List<Map.Entry<String, ProcessingMessageMailbox<X, Y>>> inactiveList = mailboxDict.entrySet().stream().filter(entry ->
                entry.getValue().isInactive(timeoutSeconds) && !entry.getValue().isRunning()
        ).collect(Collectors.toList());
        inactiveList.forEach(entry -> {
            if (mailboxDict.remove(entry.getKey()) != null) {
                logger.info("Removed inactive {} mailbox, aggregateRootId: {}", getMessageName(), entry.getKey());
            }
        });
    }
}