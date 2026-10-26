package com.example.ticketing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.paginators.QueryPublisher;

/** Atomic SDK test double; real DynamoDB expression semantics are tested separately. */
class MemoryDynamo {
  final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
  final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
  private final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

  MemoryDynamo() {
    when(client.putItem(any(PutItemRequest.class)))
        .thenAnswer(
            invocation ->
                CompletableFuture.supplyAsync(
                    () -> {
                      PutItemRequest request = invocation.getArgument(0);
                      synchronized (items) {
                        failIfRequested();
                        String key = request.item().get("pk").s();
                        if (items.containsKey(key))
                          throw ConditionalCheckFailedException.builder()
                              .message("Duplicate key")
                              .build();
                        items.put(key, Map.copyOf(request.item()));
                      }
                      return PutItemResponse.builder().build();
                    }));
    when(client.getItem(any(GetItemRequest.class)))
        .thenAnswer(
            invocation -> {
              GetItemRequest request = invocation.getArgument(0);
              synchronized (items) {
                return CompletableFuture.completedFuture(
                    GetItemResponse.builder().item(items.get(request.key().get("pk").s())).build());
              }
            });
    when(client.deleteItem(any(DeleteItemRequest.class)))
        .thenAnswer(
            invocation -> {
              DeleteItemRequest request = invocation.getArgument(0);
              synchronized (items) {
                items.remove(request.key().get("pk").s());
              }
              return CompletableFuture.completedFuture(DeleteItemResponse.builder().build());
            });
    when(client.queryPaginator(any(QueryRequest.class)))
        .thenAnswer(invocation -> new QueryPublisher(client, invocation.getArgument(0)));
    when(client.query(any(QueryRequest.class)))
        .thenAnswer(
            invocation -> {
              QueryRequest request = invocation.getArgument(0);
              synchronized (items) {
                String kind = request.expressionAttributeValues().get(":kind").s();
                AttributeValue cutoff = request.expressionAttributeValues().get(":cutoff");
                List<Map<String, AttributeValue>> matches =
                    items.values().stream()
                        .filter(item -> item.get("kind").s().equals(kind))
                        .filter(
                            item ->
                                cutoff == null || item.get("sort").s().compareTo(cutoff.s()) <= 0)
                        .sorted(Comparator.comparing(item -> item.get("sort").s()))
                        .toList();
                return CompletableFuture.completedFuture(
                    QueryResponse.builder().items(matches).build());
              }
            });
    when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
        .thenAnswer(
            invocation ->
                CompletableFuture.supplyAsync(
                    () -> {
                      TransactWriteItemsRequest request = invocation.getArgument(0);
                      synchronized (items) {
                        failIfRequested();
                        Map<String, Map<String, AttributeValue>> snapshot = new HashMap<>(items);
                        for (TransactWriteItem change : request.transactItems())
                          apply(snapshot, change);
                        items.clear();
                        items.putAll(snapshot);
                      }
                      return TransactWriteItemsResponse.builder().build();
                    }));
  }

  private void failIfRequested() {
    RuntimeException failure = nextFailure.getAndSet(null);
    if (failure != null) throw failure;
  }

  private void reject() {
    throw TransactionCanceledException.builder().message("Conditional write rejected").build();
  }

  private void apply(Map<String, Map<String, AttributeValue>> snapshot, TransactWriteItem change) {
    if (change.put() != null) {
      Put put = change.put();
      String key = put.item().get("pk").s();
      Map<String, AttributeValue> existing = snapshot.get(key);
      if (put.conditionExpression().equals("attribute_not_exists(pk)")) {
        if (existing != null) reject();
      } else if (existing == null
          || !existing.get("status").equals(put.expressionAttributeValues().get(":expected")))
        reject();
      snapshot.put(key, Map.copyOf(put.item()));
    } else if (change.conditionCheck() != null) {
      if (!snapshot.containsKey(change.conditionCheck().key().get("pk").s())) reject();
    } else {
      Update update = change.update();
      String key = update.key().get("pk").s();
      Map<String, AttributeValue> existing = snapshot.get(key);
      String source = update.expressionAttributeNames().get("#source");
      String target = update.expressionAttributeNames().get("#target");
      int quantity = Integer.parseInt(update.expressionAttributeValues().get(":quantity").n());
      if (existing == null || Integer.parseInt(existing.get(source).n()) < quantity) reject();
      Map<String, AttributeValue> updated = new HashMap<>(existing);
      updated.put(
          source,
          AttributeValue.builder()
              .n(Integer.toString(Integer.parseInt(existing.get(source).n()) - quantity))
              .build());
      updated.put(
          target,
          AttributeValue.builder()
              .n(Integer.toString(Integer.parseInt(existing.get(target).n()) + quantity))
              .build());
      snapshot.put(key, Map.copyOf(updated));
    }
  }
}
