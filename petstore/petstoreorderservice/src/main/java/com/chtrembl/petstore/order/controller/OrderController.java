package com.chtrembl.petstore.order.controller;

import com.chtrembl.petstore.order.model.Order;
import com.chtrembl.petstore.order.model.Product;
import com.chtrembl.petstore.order.service.OrderService;
import com.chtrembl.petstore.order.service.ProductService;
import com.chtrembl.petstore.order.service.ServiceBusPublisherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/petstoreorderservice/v2")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Store", description = "Pet Store Order API")
@Validated
public class OrderController {

    private final OrderService orderService;
    private final ProductService productService;
    private final ServiceBusPublisherService serviceBusPublisherService;

    @Operation(
            summary = "Place an order for a product",
            description = "Creates or updates an order in the store"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Order placed successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Order.class))),
            @ApiResponse(responseCode = "400", description = "Invalid order", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping(value = "store/order", produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Order> placeOrder(
            @Parameter(description = "Order placed for purchasing the product", required = true)
            @Valid @RequestBody Order order) {

        log.info("Incoming POST request to /petstoreorderservice/v2/store/order with order: {}", order);

        Order updatedOrder = orderService.updateOrder(order);

        // Enrich order with product details from product service
        List<Product> availableProducts = productService.getAvailableProducts();
        orderService.enrichOrderWithProductDetails(updatedOrder, availableProducts);

        log.info("Successfully processed order: {}", updatedOrder.getId());

        // Publish the updated order to Service Bus so OrderItemsReserver can
        // reserve the items; every cart update triggers a publish.
        serviceBusPublisherService.publishOrderUpdate(updatedOrder);

        return ResponseEntity.ok(updatedOrder);
    }

    @Operation(
            summary = "Find order by ID",
            description = "Returns a single order by its ID with enriched product information"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Order found successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = Order.class))),
            @ApiResponse(responseCode = "404", description = "Order not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "store/order/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Order> getOrderById(
            @Parameter(description = "ID of the order to retrieve", required = true, example = "68FAE9B1D86B794F0AE0ADD35A437428")
            @PathVariable("orderId")
            @Pattern(regexp = "^[0-9A-Fa-f]{32}$", message = "Order ID must be a 32-character hexadecimal string")
            String orderId) {

        log.info("Incoming GET request to /petstoreorderservice/v2/store/order/{}", orderId);

        Order order = orderService.getOrCreateOrder(orderId);

        // Enrich order with product details from product service
        List<Product> availableProducts = productService.getAvailableProducts();
        orderService.enrichOrderWithProductDetails(order, availableProducts);

        log.info("Successfully retrieved order: {}", order);

        return ResponseEntity.ok(order);
    }
}
