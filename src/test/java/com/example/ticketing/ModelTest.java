package com.example.ticketing;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ticketing.domain.Model.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ModelTest {
  @Test
  void ordersWithoutAllocatedTicketsDoNotPretendToBeAvailableTickets() {
    for (OrderStatus s :
        new OrderStatus[] {OrderStatus.QUEUED, OrderStatus.REJECTED, OrderStatus.EXPIRED})
      assertNull(new Order("id", "event", "user", 1, s, null, Instant.now(), false).ticketState());
    assertEquals(
        TicketState.SOLD,
        new Order("id", "event", "user", 1, OrderStatus.SOLD, null, Instant.now(), false)
            .ticketState());
  }
}
