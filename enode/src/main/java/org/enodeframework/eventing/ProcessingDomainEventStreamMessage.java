package org.enodeframework.eventing;

import org.enodeframework.messaging.IMessageProcessContext;

/**
 * @author anruence@gmail.com
 */
public class ProcessingDomainEventStreamMessage {
    private DomainEventStreamMessage message;
    private ProcessingDomainEventStreamMessageMailBox mailbox;
    private IMessageProcessContext processContext;

    public ProcessingDomainEventStreamMessage(DomainEventStreamMessage message, IMessageProcessContext processContext) {
        this.message = message;
        this.processContext = processContext;
    }

    public ProcessingDomainEventStreamMessageMailBox getMailbox() {
        return mailbox;
    }

    public void setMailbox(ProcessingDomainEventStreamMessageMailBox mailbox) {
        this.mailbox = mailbox;
    }

    public void complete() {
        processContext.notifyMessageProcessed();
        if (mailbox != null) {
            mailbox.completeRun();
        }
    }

    public DomainEventStreamMessage getMessage() {
        return message;
    }
}