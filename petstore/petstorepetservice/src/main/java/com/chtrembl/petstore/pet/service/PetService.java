package com.chtrembl.petstore.pet.service;

import com.chtrembl.petstore.pet.entity.PetEntity;
import com.chtrembl.petstore.pet.entity.TagEntity;
import com.chtrembl.petstore.pet.model.Category;
import com.chtrembl.petstore.pet.model.Pet;
import com.chtrembl.petstore.pet.model.Tag;
import com.chtrembl.petstore.pet.repository.PetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PetService {

    private final PetRepository petRepository;

    public List<Pet> findPetsByStatus(List<String> status) {
        log.info("Finding pets with status: {}", status);

        return petRepository.findByStatusIn(status).stream()
                .map(this::toDto)
                .toList();
    }

    public Optional<Pet> findPetById(Long petId) {
        log.info("Finding pet with id: {}", petId);

        return petRepository.findById(petId)
                .map(this::toDto);
    }

    public List<Pet> getAllPets() {
        log.info("Getting all pets");
        return petRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    public int getPetCount() {
        return (int) petRepository.count();
    }

    private Pet toDto(PetEntity entity) {
        return Pet.builder()
                .id(entity.getId())
                .name(entity.getName())
                .category(Category.builder()
                        .id(entity.getCategory().getId())
                        .name(entity.getCategory().getName())
                        .build())
                .photoURL(entity.getPhotoURL())
                .tags(entity.getTags().stream()
                        .map(this::toDto)
                        .toList())
                .status(Pet.Status.fromValue(entity.getStatus()))
                .build();
    }

    private Tag toDto(TagEntity entity) {
        return Tag.builder()
                .id(entity.getId())
                .name(entity.getName())
                .build();
    }
}