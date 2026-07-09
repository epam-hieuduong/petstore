package com.chtrembl.petstoreorderitemsreserver.model;

import java.util.List;

/**
 * Order payload as received from PetStoreApp. The order id is expected
 * to be the PetStoreApp session id, used to name the reservation blob.
 */
public class Order {
    private String id;
    private String email;
    private Boolean complete;
    private List<Product> products;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getComplete() {
        return complete;
    }

    public void setComplete(Boolean complete) {
        this.complete = complete;
    }

    public List<Product> getProducts() {
        return products;
    }

    public void setProducts(List<Product> products) {
        this.products = products;
    }
}
