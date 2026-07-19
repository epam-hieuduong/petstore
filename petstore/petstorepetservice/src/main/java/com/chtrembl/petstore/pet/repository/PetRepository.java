package com.chtrembl.petstore.pet.repository;

import com.chtrembl.petstore.pet.entity.PetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PetRepository extends JpaRepository<PetEntity, Long> {

    List<PetEntity> findByStatusIn(List<String> statuses);
}
