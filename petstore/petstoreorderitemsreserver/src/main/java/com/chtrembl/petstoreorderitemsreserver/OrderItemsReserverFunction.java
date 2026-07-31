package com.chtrembl.petstoreorderitemsreserver;

import com.chtrembl.petstoreorderitemsreserver.model.Order;
import com.chtrembl.petstoreorderitemsreserver.service.BlobStorageService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.ServiceBusQueueTrigger;

import java.util.logging.Logger;

/**
 * Azure Function triggered by messages on the Service Bus queue that
 * petstoreorderservice publishes to on every cart update. Uploads the order
 * (including the full product list) as a JSON file to Blob Storage to
 * "reserve" the items.
 *
 * <p>Throwing from this method leaves the Service Bus message uncompleted,
 * so it becomes visible again and is redelivered. Once the queue's configured
 * max delivery count is reached (set to 3, see infra/DEPLOYMENT_STEPS.md
 * Part B), Service Bus automatically dead-letters the message, which the
 * fallback Logic App monitors to notify the manager (see
 * infra/DEPLOYMENT_STEPS.md, Part B, step B8).</p>
 */
public class OrderItemsReserverFunction {

    @FunctionName("reserveOrderItems")
    public void reserveOrderItems(
            @ServiceBusQueueTrigger(
                    name = "message",
                    queueName = "%SERVICEBUS_QUEUE_NAME%",
                    connection = "SERVICEBUS_CONNECTION")
            String message,
            final ExecutionContext context) {

        Logger logger = context.getLogger();
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        if (message == null || message.trim().isEmpty()) {
            logger.warning("reserveOrderItems received an empty Service Bus message; nothing to reserve");
            return;
        }

        Order order;
        try {
            order = objectMapper.readValue(message, Order.class);
        } catch (Exception e) {
            logger.severe("Unable to parse order payload from Service Bus message: " + e.getMessage());
            throw new IllegalArgumentException("Invalid order payload: " + e.getMessage(), e);
        }

        if (order.getId() == null || order.getId().trim().isEmpty()) {
            logger.severe("Order payload is missing an id (session id)");
            throw new IllegalArgumentException("Order id (session id) is required");
        }

        try {
            BlobStorageService blobStorageService = new BlobStorageService(logger);
            String blobName = blobStorageService.uploadOrder(order.getId(), message);

            logger.info("Reserved order items for session " + order.getId() + " -> blob " + blobName);
        } catch (Exception e) {
            logger.severe("Failed to reserve order items for session " + order.getId() + ": " + e.getMessage());
            throw new RuntimeException("Failed to reserve order items for session " + order.getId(), e);
        }
    }
}
