package com.chtrembl.petstore.product.repository;

import com.chtrembl.petstore.product.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<ProductEntity, Long> {

    List<ProductEntity> findByStatusIn(List<String> statuses);
}
