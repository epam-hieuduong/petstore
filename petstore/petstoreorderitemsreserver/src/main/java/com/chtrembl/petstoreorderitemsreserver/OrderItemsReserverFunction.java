package com.chtrembl.petstoreorderitemsreserver;

import com.chtrembl.petstoreorderitemsreserver.model.Order;
import com.chtrembl.petstoreorderitemsreserver.model.ReservationResult;
import com.chtrembl.petstoreorderitemsreserver.service.BlobStorageService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Azure Function that receives an order payload from PetStoreApp and
 * uploads it as a JSON file to Blob Storage to "reserve" the items.
 */
public class OrderItemsReserverFunction {

    @FunctionName("reserveOrderItems")
    public HttpResponseMessage reserveOrderItems(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "reserveOrderItems")
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String body = request.getBody().orElse(null);
        if (body == null || body.trim().isEmpty()) {
            logger.warning("reserveOrderItems called with empty request body");
            return buildResponse(request, HttpStatus.BAD_REQUEST,
                    new ReservationResult(false, null, "Request body is required"));
        }

        Order order;
        try {
            order = objectMapper.readValue(body, Order.class);
        } catch (Exception e) {
            logger.warning("Unable to parse order payload: " + e.getMessage());
            return buildResponse(request, HttpStatus.BAD_REQUEST,
                    new ReservationResult(false, null, "Invalid order payload: " + e.getMessage()));
        }

        if (order.getId() == null || order.getId().trim().isEmpty()) {
            logger.warning("Order payload is missing an id (session id)");
            return buildResponse(request, HttpStatus.BAD_REQUEST,
                    new ReservationResult(false, null, "Order id (session id) is required"));
        }

        try {
            BlobStorageService blobStorageService = new BlobStorageService(logger);
            String blobName = blobStorageService.uploadOrder(order.getId(), body);

            logger.info("Reserved order items for session " + order.getId() + " -> blob " + blobName);

            return buildResponse(request, HttpStatus.OK,
                    new ReservationResult(true, blobName, "Order items reserved successfully"));
        } catch (Exception e) {
            logger.severe("Failed to reserve order items for session " + order.getId() + ": " + e.getMessage());
            return buildResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    new ReservationResult(false, null, "Failed to reserve order items: " + e.getMessage()));
        }
    }

    private HttpResponseMessage buildResponse(
            HttpRequestMessage<Optional<String>> request, HttpStatus status, ReservationResult result) {
        try {
            String json = new ObjectMapper().writeValueAsString(result);
            return request.createResponseBuilder(status)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .build();
        } catch (Exception e) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"success\":false,\"message\":\"Failed to serialize response\"}")
                    .build();
        }
    }
}
