package org.enodeframework.tests.TestClasses;

import org.enodeframework.commanding.CommandResult;
import org.enodeframework.commanding.CommandStatus;
import org.enodeframework.common.io.Task;
import org.enodeframework.common.utilities.ObjectId;
import org.enodeframework.tests.Commands.CreateTestAggregateCommand;
import org.enodeframework.tests.Mocks.FailedType;
import org.enodeframework.tests.Mocks.MockDomainEventPublisher;
import org.junit.Assert;

public class EventPublisherFailedTest extends AbstractTest {
    public void event_publisher_failed_test() {
        CreateTestAggregateCommand command = new CreateTestAggregateCommand();
        command.aggregateRootId = ObjectId.generateNewStringId();
        command.setTitle("Sample Note");
        ((MockDomainEventPublisher) domainEventPublisher).setExpectFailedCount(FailedType.UnKnownException, 5);
        CommandResult asyncResult = Task.await(commandService.executeAsync(command));
        Assert.assertNotNull(asyncResult);

        CommandResult commandResult = asyncResult;
        Assert.assertNotNull(commandResult);
        Assert.assertEquals(CommandStatus.Success, commandResult.getStatus());
        ((MockDomainEventPublisher) domainEventPublisher).Reset();
        command = new CreateTestAggregateCommand();
        command.aggregateRootId = ObjectId.generateNewStringId();
        command.setTitle("Sample Note");
        ((MockDomainEventPublisher) domainEventPublisher).setExpectFailedCount(FailedType.IOException, 5);
        asyncResult = Task.await(commandService.executeAsync(command));
        Assert.assertNotNull(asyncResult);

        commandResult = asyncResult;
        Assert.assertNotNull(commandResult);
        Assert.assertEquals(CommandStatus.Success, commandResult.getStatus());
        ((MockDomainEventPublisher) domainEventPublisher).Reset();
        command = new CreateTestAggregateCommand();
        command.aggregateRootId = ObjectId.generateNewStringId();
        command.setTitle("Sample Note");
        ((MockDomainEventPublisher) domainEventPublisher).setExpectFailedCount(FailedType.TaskIOException, 5);
        asyncResult = Task.await(commandService.executeAsync(command));
        Assert.assertNotNull(asyncResult);

        commandResult = asyncResult;
        Assert.assertNotNull(commandResult);
        Assert.assertEquals(CommandStatus.Success, commandResult.getStatus());
        ((MockDomainEventPublisher) domainEventPublisher).Reset();
    }
}
