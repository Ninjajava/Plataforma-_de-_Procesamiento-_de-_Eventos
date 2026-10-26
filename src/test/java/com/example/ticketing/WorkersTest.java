package com.example.ticketing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ticketing.application.*;
import com.example.ticketing.domain.Model.Outbox;
import com.example.ticketing.infrastructure.Workers;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

class WorkersTest {
  private TicketStore store;
  private TicketService service;
  private SqsAsyncClient queue;
  private Workers workers;

  @BeforeEach
  void setup() {
    store = mock(TicketStore.class);
    service = mock(TicketService.class);
    queue = mock(SqsAsyncClient.class);
    workers = new Workers(store, service, queue, "http://queue/orders");
  }

  @Test
  void outboxIsRemovedOnlyAfterBrokerAcknowledges() {
    when(store.outbox()).thenReturn(Flux.just(new Outbox("outbox", "order")));
    when(queue.sendMessage(any(SendMessageRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
    when(store.delivered("outbox")).thenReturn(Mono.empty());
    StepVerifier.create(workers.publish()).verifyComplete();
    var sequence = inOrder(queue, store);
    sequence.verify(queue).sendMessage(any(SendMessageRequest.class));
    sequence.verify(store).delivered("outbox");
  }

  @Test
  void failedPublishKeepsOutboxAndDoesNotStopNextScheduledCycle() {
    when(store.outbox()).thenReturn(Flux.just(new Outbox("outbox", "order")));
    when(queue.sendMessage(any(SendMessageRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Broker unavailable")));
    when(store.delivered("outbox")).thenReturn(Mono.empty());
    StepVerifier.create(workers.publish()).verifyComplete();
    verify(store, never()).delivered(anyString());
    verify(queue).sendMessage(any(SendMessageRequest.class));
  }

  @Test
  void processedMessagesAreDeletedAfterCommit() {
    Message message = Message.builder().body("order").receiptHandle("receipt").build();
    when(queue.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                ReceiveMessageResponse.builder().messages(message).build()));
    when(service.reserve("order")).thenReturn(Mono.empty());
    when(queue.deleteMessage(any(DeleteMessageRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
    StepVerifier.create(workers.consume()).verifyComplete();
    var sequence = inOrder(service, queue);
    sequence.verify(service).reserve("order");
    sequence.verify(queue).deleteMessage(any(DeleteMessageRequest.class));
  }

  @Test
  void failedProcessingDoesNotAcknowledgeMessage() {
    when(queue.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                ReceiveMessageResponse.builder()
                    .messages(Message.builder().body("order").receiptHandle("receipt").build())
                    .build()));
    when(service.reserve("order")).thenReturn(Mono.error(new RuntimeException("Commit failed")));
    StepVerifier.create(workers.consume()).verifyComplete();
    verify(queue, never()).deleteMessage(any(DeleteMessageRequest.class));
  }

  @Test
  void receiveFailureAndExpirationFailureAreRecoveredForNextCycle() {
    when(queue.receiveMessage(any(ReceiveMessageRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Broker unavailable")));
    when(service.expire())
        .thenReturn(Mono.error(new RuntimeException("Store unavailable")), Mono.empty());
    StepVerifier.create(workers.consume()).verifyComplete();
    StepVerifier.create(workers.expire()).verifyComplete();
    StepVerifier.create(workers.expire()).verifyComplete();
    verify(service, times(2)).expire();
  }
}
