package com.example.ticketing.infrastructure;

import com.example.ticketing.application.TicketService;
import com.example.ticketing.application.TicketStore;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

@Component
public class Workers {
  private static final Logger log = LoggerFactory.getLogger(Workers.class);
  private final TicketStore store;
  private final TicketService service;
  private final SqsAsyncClient sqs;
  private final String url;

  public Workers(
      TicketStore store,
      TicketService service,
      SqsAsyncClient sqs,
      @Value("${queue.url}") String url) {
    this.store = store;
    this.service = service;
    this.sqs = sqs;
    this.url = url;
  }

  @Scheduled(fixedDelay = 500)
  public Mono<Void> publish() {
    return store
        .outbox()
        .flatMap(
            outbox ->
                Mono.fromFuture(
                        () ->
                            sqs.sendMessage(
                                SendMessageRequest.builder()
                                    .queueUrl(url)
                                    .messageBody(outbox.orderId())
                                    .build()))
                    .then(Mono.defer(() -> store.delivered(outbox.id()))),
            8)
        .then()
        .doOnError(error -> log.error("Outbox failed; next cycle will retry", error))
        .onErrorComplete();
  }

  @Scheduled(fixedDelay = 100)
  public Mono<Void> consume() {
    return Mono.fromFuture(
            () ->
                sqs.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(url)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(10)
                        .visibilityTimeout(60)
                        .build()))
        .flatMapMany(response -> Flux.fromIterable(response.messages()))
        .flatMap(
            message ->
                service
                    .reserve(message.body())
                    .then(
                        Mono.fromFuture(
                            () ->
                                sqs.deleteMessage(
                                    DeleteMessageRequest.builder()
                                        .queueUrl(url)
                                        .receiptHandle(message.receiptHandle())
                                        .build())))
                    .doOnError(
                        error -> log.error("Order {} awaiting redelivery", message.body(), error))
                    .onErrorComplete(),
            10)
        .then()
        .doOnError(error -> log.error("SQS unavailable", error))
        .onErrorComplete();
  }

  @Scheduled(fixedDelay = 1000)
  public Mono<Void> expire() {
    return Mono.defer(service::expire)
        .doOnError(error -> log.error("Expiration failed; next cycle will retry", error))
        .onErrorComplete();
  }
}
