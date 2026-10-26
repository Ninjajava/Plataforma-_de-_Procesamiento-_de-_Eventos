package com.example.ticketing.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.example.ticketing.application.TicketStore;
import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

class ConfigurationTest {
  @Test
  void clientsUseConfiguredRegionAndEndpointWithoutStaticCredentials() {
    Configuration configuration = new Configuration();
    try (var database = configuration.dynamo("http://localhost:8000", "eu-west-1");
        var queue = configuration.sqs("http://localhost:4566", "eu-west-1")) {
      assertEquals(Region.EU_WEST_1, database.serviceClientConfiguration().region());
      assertEquals(Region.EU_WEST_1, queue.serviceClientConfiguration().region());
      assertEquals(
          URI.create("http://localhost:8000"),
          database.serviceClientConfiguration().endpointOverride().orElseThrow());
      assertEquals(
          URI.create("http://localhost:4566"),
          queue.serviceClientConfiguration().endpointOverride().orElseThrow());
    }
    assertNotNull(configuration.clock());
    assertNotNull(configuration.ticketService(mock(TicketStore.class), configuration.clock()));
  }
}
