package com.chtrembl.petstore.order.model;

import com.azure.spring.data.cosmos.core.mapping.Container;
import com.azure.spring.data.cosmos.core.mapping.PartitionKey;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = {"products"})
@Container(containerName = "orders")
@Schema(description = "Order entity representing a customer order")
public class Order {

	@Id
	@PartitionKey
	@NotNull(message = "Order ID cannot be null")
	@Pattern(
			regexp = "^[0-9A-Fa-f]{32}$",
			message = "Order ID must be a 32-character hexadecimal string"
	)
	@Schema(
			description = "Order identifier (typically session ID)",
			example = "68FAE9B1D86B794F0AE0ADD35A437428"
	)
	private String id;

	@Size(max = 255, message = "Email must not exceed 255 characters")
	@Schema(description = "Customer email address",
			example = "customer@example.com")
	private String email;

	@Valid
	@Builder.Default
	@Schema(description = "List of products in the order")
	private List<Product> products = new ArrayList<>();

	@Schema(description = "Order status", example = "placed")
	private Status status;

	@Builder.Default
	@Schema(description = "Whether the order is completed", example = "false")
	private Boolean complete = false;

	public Boolean getComplete() {
		return complete != null ? complete : false;
	}

	public void setComplete(Boolean complete) {
		this.complete = complete != null ? complete : false;
	}

	public List<Product> getProducts() {
		return products != null ? products : new ArrayList<>();
	}

	public void setProducts(List<Product> products) {
		this.products = products != null ? products : new ArrayList<>();
	}

	/**
	 * Order Status
	 */
	public enum Status {
		PLACED("placed"),
		APPROVED("approved"),
		DELIVERED("delivered");

		private final String value;

		Status(String value) {
			this.value = value;
		}

		@Override
		@JsonValue
		public String toString() {
			return String.valueOf(value);
		}

		@JsonCreator
		public static Status fromValue(String text) {
			if (text == null) {
				return null;
			}

			for (Status status : Status.values()) {
				if (String.valueOf(status.value).equalsIgnoreCase(text.trim())) {
					return status;
				}
			}
			return null;
		}
	}
}