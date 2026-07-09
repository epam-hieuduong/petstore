package com.chtrembl.petstoreorderitemsreserver.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Uploads order reservation JSON payloads to Azure Blob Storage.
 * Each session gets a single blob (named after the session id) which is
 * overwritten on every subsequent cart update for that session.
 */
public class BlobStorageService {

    private static final String DEFAULT_CONTAINER_NAME = "orderitemsreserver";

    private final BlobContainerClient containerClient;

    public BlobStorageService(Logger logger) {
        String connectionString = System.getenv("BLOB_STORAGE_CONNECTION_STRING");
        if (connectionString == null || connectionString.trim().isEmpty()) {
            throw new IllegalStateException("BLOB_STORAGE_CONNECTION_STRING is not configured");
        }

        String containerName = System.getenv("BLOB_STORAGE_CONTAINER_NAME");
        if (containerName == null || containerName.trim().isEmpty()) {
            containerName = DEFAULT_CONTAINER_NAME;
        }

        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();

        BlobContainerClient client = serviceClient.getBlobContainerClient(containerName);
        if (!client.exists()) {
            logger.info("Blob container '" + containerName + "' does not exist, creating it");
            client.create();
        }
        this.containerClient = client;
    }

    /**
     * Uploads (or overwrites) the JSON blob for the given session id.
     *
     * @param sessionId the PetStoreApp session id, used to name the blob
     * @param orderJson the serialized order JSON to upload
     * @return the resulting blob name
     */
    public String uploadOrder(String sessionId, String orderJson) {
        String blobName = "order-" + sessionId + ".json";
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        byte[] bytes = orderJson.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream dataStream = new ByteArrayInputStream(bytes)) {
            blobClient.upload(dataStream, bytes.length, true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload order blob: " + blobName, e);
        }

        return blobName;
    }
}
