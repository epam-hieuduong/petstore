package com.chtrembl.petstoreapp.service;

import com.chtrembl.petstoreapp.client.OrderItemsReserverClient;
import com.chtrembl.petstoreapp.client.OrderServiceClient;
import com.chtrembl.petstoreapp.exception.OrderServiceException;
import com.chtrembl.petstoreapp.model.Order;
import com.chtrembl.petstoreapp.model.Product;
import com.chtrembl.petstoreapp.model.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.chtrembl.petstoreapp.config.Constants.COMPLETE_ORDER;
import static com.chtrembl.petstoreapp.config.Constants.OPERATION;
import static com.chtrembl.petstoreapp.config.Constants.ORDER_ID;
import static com.chtrembl.petstoreapp.config.Constants.PRODUCT_ID;
import static com.chtrembl.petstoreapp.config.Constants.QUANTITY;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderManagementService {

    private final User sessionUser;
    private final OrderServiceClient orderServiceClient;
    private final OrderItemsReserverClient orderItemsReserverClient;

    public void updateOrder(long productId, int quantity, boolean completeOrder) {
        MDC.put(OPERATION, "updateOrder");
        MDC.put(PRODUCT_ID, String.valueOf(productId));
        MDC.put(QUANTITY, String.valueOf(quantity));
        MDC.put(COMPLETE_ORDER, String.valueOf(completeOrder));

        this.sessionUser.getTelemetryClient()
                .trackEvent(String.format(
                        "PetStoreApp user %s is trying to update an order",
                        this.sessionUser.getName()), this.sessionUser.getCustomEventProperties(), null);

        try {
            Order updatedOrder = buildOrderUpdate(productId, quantity, completeOrder);
            String orderJSON = serializeOrder(updatedOrder);

            Order resultOrder = orderServiceClient.createOrUpdateOrder(orderJSON);
            log.info("Successfully updated order: {}", resultOrder);

            reserveOrderItems(resultOrder);

        } catch (FeignException fe) {
            log.error("Unable to update order via Feign client: HTTP {} - {}", fe.status(), fe.getMessage(), fe);
            this.sessionUser.getTelemetryClient().trackException(fe);
            throw new OrderServiceException("Unable to update order via order service", fe);
        } catch (Exception e) {
            log.error("Unexpected error updating order", e);
            this.sessionUser.getTelemetryClient().trackException(e);
            throw new OrderServiceException("Unable to update order via order service", e);
        } finally {
            cleanupMDC();
        }
    }

    public Order retrieveOrder(String orderId) {
        MDC.put(OPERATION, "retrieveOrder");
        MDC.put(ORDER_ID, orderId);

        this.sessionUser.getTelemetryClient()
                .trackEvent(String.format(
                        "PetStoreApp user %s is requesting to retrieve an order from the PetStoreOrderService",
                        this.sessionUser.getName()), this.sessionUser.getCustomEventProperties(), null);

        try {
            Order order = orderServiceClient.getOrder(orderId);
            log.info("Successfully retrieved order: {}", order);
            return order;

        } catch (FeignException.NotFound e) {
            log.debug("Order not found: {}, returning empty order", orderId);
            Order newOrder = new Order();
            newOrder.setId(orderId);
            newOrder.setComplete(false);
            newOrder.setProducts(new ArrayList<>());
            return newOrder;
        } catch (FeignException fe) {
            log.error("Unable to retrieve order via Feign client: HTTP {} - {}", fe.status(), fe.getMessage(), fe);
            this.sessionUser.getTelemetryClient().trackException(fe);
            throw new OrderServiceException("Unable to retrieve order from order service", fe);
        } catch (Exception e) {
            log.error("Unexpected error retrieving order: {}", orderId, e);
            this.sessionUser.getTelemetryClient().trackException(e);
            throw new OrderServiceException("Unable to retrieve order from order service", e);
        } finally {
            MDC.remove(OPERATION);
            MDC.remove(ORDER_ID);
        }
    }

    private Order buildOrderUpdate(long productId, int quantity, boolean completeOrder) {
        Order updatedOrder = new Order();
        updatedOrder.setId(this.sessionUser.getSessionId());

        String userEmail = this.sessionUser.getEmail();
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            updatedOrder.setEmail(userEmail);
            log.info("Setting order email to: {}", userEmail);
        } else {
            log.warn("User email is not available for session: {}", this.sessionUser.getSessionId());
        }

        if (completeOrder) {
            updatedOrder.setComplete(true);
            log.info("Completing order for session: {}", this.sessionUser.getSessionId());
        } else {
            List<Product> products = new ArrayList<>();
            Product product = new Product();
            product.setId(productId);
            product.setQuantity(quantity);
            products.add(product);
            updatedOrder.setProducts(products);
            log.info("Adding/updating product {} with quantity {} to order", productId, quantity);
        }

        return updatedOrder;
    }

    private String serializeOrder(Order order) throws Exception {
        return new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false)
                .writeValueAsString(order);
    }

    private void cleanupMDC() {
        MDC.remove(OPERATION);
        MDC.remove(PRODUCT_ID);
        MDC.remove(QUANTITY);
        MDC.remove(COMPLETE_ORDER);
    }

    /**
     * Sends the current order including the full product list to the
     * OrderItemsReserver Azure Function so it can reserve items by uploading
     * the order as JSON to Azure Blob Storage. Called on every cart update.
     * Failures are logged/tracked but never block the cart update.
     */
    private void reserveOrderItems(Order order) {
        try {
            Order orderToReserve = order;
            if (orderToReserve == null || orderToReserve.getProducts() == null) {
                orderToReserve = orderServiceClient.getOrder(this.sessionUser.getSessionId());
            }

            if (orderToReserve == null || orderToReserve.getProducts() == null || orderToReserve.getProducts().isEmpty()) {
                log.info("No products in order to reserve for session: {}", this.sessionUser.getSessionId());
                return;
            }

            String orderJSON = serializeOrder(orderToReserve);
            log.info("Calling OrderItemsReserver to reserve items for order: {}", orderToReserve.getId());

            String reservationResult = orderItemsReserverClient.reserveOrderItems(orderJSON);
            log.info("OrderItemsReserver response for order {}: {}", orderToReserve.getId(), reservationResult);

            this.sessionUser.getTelemetryClient()
                    .trackEvent(String.format(
                            "PetStoreApp user %s successfully reserved order items via OrderItemsReserver",
                            this.sessionUser.getName()), this.sessionUser.getCustomEventProperties(), null);
        } catch (FeignException fe) {
            log.warn("OrderItemsReserver call failed (HTTP {}): {} - order will proceed anyway",
                    fe.status(), fe.getMessage());
            this.sessionUser.getTelemetryClient().trackException(fe);
        } catch (Exception e) {
            log.warn("OrderItemsReserver call failed: {} - order will proceed anyway", e.getMessage());
            this.sessionUser.getTelemetryClient().trackException(e);
        }
    }
}
