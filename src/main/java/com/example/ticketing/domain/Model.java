package com.example.ticketing.domain;

import java.time.Instant;

public final class Model {
  private Model() {}

  public enum TicketState {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY
  }

  public enum OrderStatus {
    QUEUED,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY,
    EXPIRED,
    REJECTED
  }

  public record Event(
      String id,
      String name,
      Instant date,
      String venue,
      int capacity,
      int available,
      int reserved,
      int pending,
      int sold,
      int complimentary) {}

  public record Order(
      String id,
      String eventId,
      String customerId,
      int quantity,
      OrderStatus status,
      Instant expiresAt,
      Instant createdAt,
      boolean courtesy) {
    public TicketState ticketState() {
      return switch (status) {
        case RESERVED -> TicketState.RESERVED;
        case PENDING_CONFIRMATION -> TicketState.PENDING_CONFIRMATION;
        case SOLD -> TicketState.SOLD;
        case COMPLIMENTARY -> TicketState.COMPLIMENTARY;
        default -> null;
      };
    }
  }

  public record Audit(String id, String orderId, String from, String to, Instant at) {}

  public record Outbox(String id, String orderId) {}

  public static class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }

  public static class Missing extends RuntimeException {
    public Missing(String message) {
      super(message);
    }
  }

  public static class ConcurrentChange extends RuntimeException {
    public ConcurrentChange(Throwable cause) {
      super("Concurrent inventory or order change", cause);
    }
  }

  public static class TransientStoreFailure extends RuntimeException {
    public TransientStoreFailure(Throwable cause) {
      super("Temporary persistence failure", cause);
    }
  }
}
