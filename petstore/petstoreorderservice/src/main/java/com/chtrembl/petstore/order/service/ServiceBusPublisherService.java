package com.chtrembl.petstore.order.service;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.chtrembl.petstore.order.model.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Publishes order updates to the Azure Service Bus queue consumed by the
 * OrderItemsReserver Function. Called every time a customer updates their
 * shopping cart (createOrUpdateOrder / placeOrder). Publish failures are
 * logged but never block or fail the caller's request - the queue send is
 * best-effort, matching the previous direct-HTTP-call semantics it replaces.
 */
@Service
@Slf4j
public class ServiceBusPublisherService {

    private final String connectionString;
    private final String queueName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile ServiceBusSenderClient senderClient;

    public ServiceBusPublisherService(
            @Value("${servicebus.connection-string:}") String connectionString,
            @Value("${servicebus.queue-name:order-items-reservation}") String queueName) {
        this.connectionString = connectionString;
        this.queueName = queueName;
    }

    /**
     * Serializes the order (including the full product list) and sends it
     * to the Service Bus queue so the OrderItemsReserver Function can
     * reserve the items by uploading the order as JSON to Blob Storage.
     */
    public void publishOrderUpdate(Order order) {
        if (!StringUtils.hasText(connectionString)) {
            log.warn("SERVICEBUS_CONNECTION_STRING is not configured; skipping Service Bus publish for order {}",
                    order.getId());
            return;
        }

        try {
            String orderJson = objectMapper.writeValueAsString(order);

            ServiceBusMessage message = new ServiceBusMessage(orderJson);
            message.setContentType("application/json");
            message.setMessageId(order.getId() + "-" + System.currentTimeMillis());
            message.getApplicationProperties().put("sessionId", order.getId());

            getOrCreateSenderClient().sendMessage(message);

            log.info("Published order update to Service Bus queue '{}' for session {}", queueName, order.getId());
        } catch (Exception e) {
            log.warn("Failed to publish order update to Service Bus for session {}: {}",
                    order.getId(), e.getMessage(), e);
        }
    }

    private ServiceBusSenderClient getOrCreateSenderClient() {
        if (senderClient == null) {
            synchronized (this) {
                if (senderClient == null) {
                    senderClient = new ServiceBusClientBuilder()
                            .connectionString(connectionString)
                            .sender()
                            .queueName(queueName)
                            .buildClient();
                }
            }
        }
        return senderClient;
    }

    @PreDestroy
    public void close() {
        if (senderClient != null) {
            senderClient.close();
        }
    }
}
