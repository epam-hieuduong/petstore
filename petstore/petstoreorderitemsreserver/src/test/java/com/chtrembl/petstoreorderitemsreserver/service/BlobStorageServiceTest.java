package com.chtrembl.petstoreorderitemsreserver.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtrembl.petstoreorderitemsreserver.model.Order;
import com.chtrembl.petstoreorderitemsreserver.model.Product;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for requirements 5 and 6 (the blob is named after the
 * session id and overwritten on every update, and the full product list
 * survives the round trip from the incoming Service Bus message JSON), and
 * requirement 7 (up to 3 upload attempts before failing the invocation).
 */
public class BlobStorageServiceTest {

    @Test
    public void uploadOrderNamesBlobAfterSessionIdAndOverwritesExistingBlob() {
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        when(containerClient.getBlobClient("order-abc123.json")).thenReturn(blobClient);

        BlobStorageService service = new BlobStorageService(containerClient);

        String blobName = service.uploadOrder("abc123", "{\"id\":\"abc123\"}");

        assertEquals("order-abc123.json", blobName);
        // overwrite flag must be true so a second update for the same session replaces the blob
        verify(blobClient).upload(org.mockito.ArgumentMatchers.any(InputStream.class), anyLong(), eq(true));
    }

    @Test
    public void secondUpdateForSameSessionReusesSameBlobName() {
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        when(containerClient.getBlobClient("order-session-42.json")).thenReturn(blobClient);

        BlobStorageService service = new BlobStorageService(containerClient);

        String firstBlobName = service.uploadOrder("session-42", "{\"id\":\"session-42\",\"products\":[]}");
        String secondBlobName = service.uploadOrder("session-42", "{\"id\":\"session-42\",\"products\":[{\"id\":1,\"quantity\":2}]}");

        assertEquals(firstBlobName, secondBlobName);
        verify(containerClient, org.mockito.Mockito.times(2)).getBlobClient("order-session-42.json");
    }

    @Test
    public void uploadOrderRetriesOnTransientFailureAndSucceedsWithinThreeAttempts() throws Exception {
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        when(containerClient.getBlobClient("order-retry-session.json")).thenReturn(blobClient);

        doThrow(new RuntimeException("transient network error"))
                .doThrow(new RuntimeException("transient network error"))
                .doNothing()
                .when(blobClient).upload(org.mockito.ArgumentMatchers.any(InputStream.class), anyLong(), eq(true));

        BlobStorageService service = new BlobStorageService(containerClient);

        String blobName = service.uploadOrder("retry-session", "{\"id\":\"retry-session\"}");

        assertEquals("order-retry-session.json", blobName);
        verify(blobClient, times(3)).upload(org.mockito.ArgumentMatchers.any(InputStream.class), anyLong(), eq(true));
    }

    @Test
    public void uploadOrderThrowsAfterExhaustingThreeAttempts() {
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        when(containerClient.getBlobClient("order-always-failing.json")).thenReturn(blobClient);

        doThrow(new RuntimeException("blob storage unavailable"))
                .when(blobClient).upload(org.mockito.ArgumentMatchers.any(InputStream.class), anyLong(), eq(true));

        BlobStorageService service = new BlobStorageService(containerClient);

        try {
            service.uploadOrder("always-failing", "{\"id\":\"always-failing\"}");
            fail("Expected uploadOrder to throw after exhausting retry attempts");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("attempt 3/3"));
        }

        verify(blobClient, times(3)).upload(org.mockito.ArgumentMatchers.any(InputStream.class), anyLong(), eq(true));
    }

    @Test
    public void fullProductListSurvivesDeserializationFromServiceBusMessage() throws Exception {
        String messageBody = "{"
                + "\"id\":\"session-99\","
                + "\"email\":\"user@example.com\","
                + "\"complete\":false,"
                + "\"products\":["
                + "  {\"id\":1,\"name\":\"Ball\",\"quantity\":2},"
                + "  {\"id\":2,\"name\":\"Leash\",\"quantity\":1}"
                + "]"
                + "}";

        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        Order order = objectMapper.readValue(messageBody, Order.class);

        List<Product> products = order.getProducts();
        assertEquals(2, products.size());
        assertTrue(products.stream().anyMatch(p -> p.getId().equals(1L) && p.getQuantity().equals(2)));
        assertTrue(products.stream().anyMatch(p -> p.getId().equals(2L) && p.getQuantity().equals(1)));
    }
}
