package com.chtrembl.petstoreorderitemsreserver;

import com.chtrembl.petstoreorderitemsreserver.model.Order;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Regression test: PetStoreApp's Order/Product payload includes extra fields
 * (category, photoURL, tags, status) that don't exist on this module's
 * slimmer DTOs. The ObjectMapper used by OrderItemsReserverFunction must be
 * configured to ignore unknown properties, otherwise deserialization fails
 * for every real request coming from PetStoreApp.
 */
public class OrderDeserializationTest {

    private static final String PETSTOREAPP_ORDER_JSON = "{"
            + "\"id\":\"abc123sessionid\","
            + "\"email\":\"user@example.com\","
            + "\"complete\":false,"
            + "\"status\":\"placed\","
            + "\"products\":[{"
            + "  \"id\":42,"
            + "  \"name\":\"Dog Food\","
            + "  \"quantity\":3,"
            + "  \"category\":{\"id\":1,\"name\":\"food\"},"
            + "  \"photoURL\":\"http://example.com/photo.png\","
            + "  \"tags\":[{\"id\":1,\"name\":\"large\"}]"
            + "}]"
            + "}";

    @Test
    public void deserializesOrderIgnoringUnknownPetStoreAppFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        Order order = objectMapper.readValue(PETSTOREAPP_ORDER_JSON, Order.class);

        assertNotNull(order);
        assertEquals("abc123sessionid", order.getId());
        assertEquals("user@example.com", order.getEmail());
        assertFalse(order.getComplete());

        List<com.chtrembl.petstoreorderitemsreserver.model.Product> products = order.getProducts();
        assertNotNull(products);
        assertEquals(1, products.size());
        assertEquals(Long.valueOf(42), products.get(0).getId());
        assertEquals("Dog Food", products.get(0).getName());
        assertEquals(Integer.valueOf(3), products.get(0).getQuantity());
    }
}
