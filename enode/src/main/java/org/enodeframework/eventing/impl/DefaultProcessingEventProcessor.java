package org.enodeframework.eventing.impl;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.enodeframework.common.exception.ENodeRuntimeException;
import org.enodeframework.common.io.IOHelper;
import org.enodeframework.common.io.Task;
import org.enodeframework.common.scheduling.IScheduleService;
import org.enodeframework.eventing.DomainEventStreamMessage;
import org.enodeframework.eventing.EnqueueMessageResult;
import org.enodeframework.eventing.IProcessingEventProcessor;
import org.enodeframework.eventing.IPublishedVersionStore;
import org.enodeframework.eventing.ProcessingEvent;
import org.enodeframework.eventing.ProcessingEventMailBox;
import org.enodeframework.messaging.IMessageDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author anruence@gmail.com
 */
public class DefaultProcessingEventProcessor implements IProcessingEventProcessor {
    private static final Logger logger = LoggerFactory.getLogger(DefaultProcessingEventProcessor.class);

    private int timeoutSeconds = 3600 * 24 * 3;

    private int scanExpiredAggregateIntervalMilliseconds = 5000;

    private String scanInactiveMailBoxTaskName;

    private String processTryToRefreshAggregateTaskName;

    private int processTryToRefreshAggregateIntervalMilliseconds = 1000;

    private ConcurrentHashMap<String, ProcessingEventMailBox> toRefreshAggregateRootMailBoxDict;

    private String name = "DefaultEventProcessor";

    private ConcurrentHashMap<String, ProcessingEventMailBox> mailboxDict;

    private ConcurrentHashMap<String, Boolean> refreshingAggregateRootDict;


    @Autowired
    private IScheduleService scheduleService;

    @Autowired
    private IMessageDispatcher dispatcher;

    @Autowired
    private IPublishedVersionStore publishedVersionStore;

    public DefaultProcessingEventProcessor() {
        mailboxDict = new ConcurrentHashMap<>();
        toRefreshAggregateRootMailBoxDict = new ConcurrentHashMap<>();
        refreshingAggregateRootDict = new ConcurrentHashMap<>();
        scanInactiveMailBoxTaskName = "CleanInactiveProcessingEventMailBoxes_" + System.currentTimeMillis() + new Random().nextInt(10000);
        processTryToRefreshAggregateTaskName = "ProcessTryToRefreshAggregate_" + System.currentTimeMillis() + new Random().nextInt(10000);

    }


    @Override
    public void process(ProcessingEvent processingMessage) {
        String aggregateRootId = processingMessage.getMessage().getAggregateRootId();
        if (Strings.isNullOrEmpty(aggregateRootId)) {
            throw new IllegalArgumentException("aggregateRootId of domain event stream cannot be null or empty, domainEventStreamId:" + processingMessage.getMessage().getId());
        }
        ProcessingEventMailBox mailbox = mailboxDict.computeIfAbsent(aggregateRootId, key -> buildProcessingEventMailBox(processingMessage));
        long mailboxTryUsingCount = 0L;
        while (!mailbox.tryUsing()) {
            Task.sleep(1);
            mailboxTryUsingCount++;
            if (mailboxTryUsingCount % 10000 == 0) {
                logger.warn("Event mailbox try using count: {}, aggregateRootId: {}, aggregateRootTypeName: {}", mailboxTryUsingCount, mailbox.getAggregateRootId(), mailbox.getAggregateRootTypeName());
            }
        }
        if (mailbox.isRemoved()) {
            mailbox = buildProcessingEventMailBox(processingMessage);
            mailboxDict.putIfAbsent(aggregateRootId, mailbox);
        }
        EnqueueMessageResult enqueueResult = mailbox.enqueueMessage(processingMessage);
        if (enqueueResult == EnqueueMessageResult.Ignored) {
            processingMessage.getProcessContext().notifyEventProcessed();
        } else if (enqueueResult == EnqueueMessageResult.AddToWaitingList) {
            addToRefreshAggregateMailBoxToDict(mailbox);
        }
        mailbox.exitUsing();
    }

    private void addToRefreshAggregateMailBoxToDict(ProcessingEventMailBox mailbox) {
        if (toRefreshAggregateRootMailBoxDict.putIfAbsent(mailbox.getAggregateRootId(), mailbox) == null) {
            logger.info("Added toRefreshPublishedVersion aggregate mailbox, aggregateRootTypeName: {}, aggregateRootId: {}", mailbox.getAggregateRootTypeName(), mailbox.getAggregateRootId());
            tryToRefreshAggregateMailBoxNextExpectingEventVersion(mailbox);
        }
    }

    private ProcessingEventMailBox buildProcessingEventMailBox(ProcessingEvent processingMessage) {
        return new ProcessingEventMailBox(processingMessage.getMessage().getAggregateRootTypeName(), processingMessage.getMessage().getAggregateRootId(), y -> dispatchProcessingMessageAsync(y, 0));
    }

    private void tryToRefreshAggregateMailBoxNextExpectingEventVersion(ProcessingEventMailBox processingEventMailBox) {
        if (refreshingAggregateRootDict.putIfAbsent(processingEventMailBox.getAggregateRootId(), true) == null) {
            getAggregateRootLatestHandledEventVersion(processingEventMailBox.getAggregateRootTypeName(), processingEventMailBox.getAggregateRootId()).thenAccept(latestPublishedEventVersion -> {
                processingEventMailBox.setNextExpectingEventVersion(latestPublishedEventVersion + 1);
                refreshingAggregateRootDict.remove(processingEventMailBox.getAggregateRootId());
            });
        }
    }

    @Override
    public void start() {
        scheduleService.startTask(scanInactiveMailBoxTaskName, this::cleanInactiveMailbox, scanExpiredAggregateIntervalMilliseconds, scanExpiredAggregateIntervalMilliseconds);
        scheduleService.startTask(processTryToRefreshAggregateTaskName, this::processToRefreshAggregateRootMailBoxs, processTryToRefreshAggregateIntervalMilliseconds, processTryToRefreshAggregateIntervalMilliseconds);
    }

    @Override
    public void stop() {
        scheduleService.stopTask(scanInactiveMailBoxTaskName);
        scheduleService.stopTask(processTryToRefreshAggregateTaskName);
    }

    /**
     * The name of the processor
     */
    @Override
    public String getName() {
        return name;
    }

    private void dispatchProcessingMessageAsync(ProcessingEvent processingEvent, int retryTimes) {
        IOHelper.tryAsyncActionRecursivelyWithoutResult("DispatchProcessingMessageAsync",
                () -> dispatcher.dispatchMessagesAsync(processingEvent.getMessage().getEvents()),
                result -> {
                    updatePublishedVersionAsync(processingEvent, 0);
                },
                () -> String.format("sequence message [messageId:%s, messageType:%s, aggregateRootId:%s, aggregateRootVersion:%s]", processingEvent.getMessage().getId(), processingEvent.getMessage().getClass().getName(), processingEvent.getMessage().getAggregateRootId(), processingEvent.getMessage().getVersion()),
                null,
                retryTimes, true);
    }

    private CompletableFuture<Integer> getAggregateRootLatestHandledEventVersion(String aggregateRootType, String aggregateRootId) {
        try {
            return publishedVersionStore.getPublishedVersionAsync(name, aggregateRootType, aggregateRootId);
        } catch (Exception ex) {
            throw new ENodeRuntimeException("_publishedVersionStore.GetPublishedVersionAsync has unknown exception.", ex);
        }
    }

    private void updatePublishedVersionAsync(ProcessingEvent processingEvent, int retryTimes) {
        DomainEventStreamMessage message = processingEvent.getMessage();
        IOHelper.tryAsyncActionRecursivelyWithoutResult("UpdatePublishedVersionAsync",
                () -> publishedVersionStore.updatePublishedVersionAsync(name, message.getAggregateRootTypeName(), message.getAggregateRootId(), message.getVersion()),
                result -> {
                    processingEvent.complete();
                },
                () -> String.format("DomainEventStreamMessage [messageId:%s, messageType:%s, aggregateRootId:%s, aggregateRootVersion:%s]", message.getId(), message.getClass().getName(), message.getAggregateRootId(), message.getVersion()),
                null, retryTimes, true);
    }


    private void processToRefreshAggregateRootMailBoxs() {
        List<ProcessingEventMailBox> remainingMailboxList = Lists.newArrayList();
        List<ProcessingEventMailBox> recoveredMailboxList = Lists.newArrayList();
        toRefreshAggregateRootMailBoxDict.values().forEach(aggregateRootMailBox -> {
            if (aggregateRootMailBox.getWaitingMessageCount() > 0) {
                remainingMailboxList.add(aggregateRootMailBox);
            } else {
                recoveredMailboxList.add(aggregateRootMailBox);
            }
        });
        for (ProcessingEventMailBox mailBox : remainingMailboxList) {
            tryToRefreshAggregateMailBoxNextExpectingEventVersion(mailBox);
        }
        for (ProcessingEventMailBox mailBox : recoveredMailboxList) {
            ProcessingEventMailBox removed = toRefreshAggregateRootMailBoxDict.remove(mailBox.getAggregateRootId());
            if (removed != null) {
                logger.info("Removed healthy aggregate mailbox, aggregateRootTypeName: {}, aggregateRootId: {}", removed.getAggregateRootTypeName(), removed.getAggregateRootId());
            }
        }
    }

    private void cleanInactiveMailbox() {
        List<Map.Entry<String, ProcessingEventMailBox>> inactiveList = mailboxDict.entrySet().stream()
                .filter(x -> isMailBoxAllowRemove(x.getValue()))
                .collect(Collectors.toList());
        inactiveList.forEach(entry -> {
            if (entry.getValue().tryUsing()) {
                if (isMailBoxAllowRemove(entry.getValue())) {
                    ProcessingEventMailBox removed = mailboxDict.remove(entry.getKey());
                    if (removed != null) {
                        removed.markAsRemoved();
                        logger.info("Removed inactive domain event stream mailbox, aggregateRootTypeName: {}, aggregateRootId: {}", removed.getAggregateRootTypeName(), removed.getAggregateRootId());
                    }
                }
            }
        });
    }

    private boolean isMailBoxAllowRemove(ProcessingEventMailBox mailbox) {
        return mailbox.isInactive(timeoutSeconds)
                && !mailbox.isRunning()
                && mailbox.getTotalUnHandledMessageCount() == 0
                && mailbox.getWaitingMessageCount() == 0;
    }
}
