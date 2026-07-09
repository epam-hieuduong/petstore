package com.chtrembl.petstoreapp.client;

import com.chtrembl.petstoreapp.config.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "order-items-reserver",
        url = "${petstore.service.orderitemsreserver.url}",
        configuration = FeignConfig.class
)
public interface OrderItemsReserverClient {

    @PostMapping("/api/reserveOrderItems")
    String reserveOrderItems(@RequestBody String orderJson);
}
