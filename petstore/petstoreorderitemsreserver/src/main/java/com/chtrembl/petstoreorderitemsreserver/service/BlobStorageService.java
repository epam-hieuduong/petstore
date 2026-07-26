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

    private static final int MAX_UPLOAD_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MILLIS = 200;

    private final BlobContainerClient containerClient;
    private final Logger logger;

    public BlobStorageService(Logger logger) {
        this(buildContainerClient(logger), logger);
    }

    /**
     * Package-private constructor allowing tests to inject a mocked
     * {@link BlobContainerClient} without hitting real Azure Storage.
     */
    BlobStorageService(BlobContainerClient containerClient) {
        this(containerClient, Logger.getLogger(BlobStorageService.class.getName()));
    }

    BlobStorageService(BlobContainerClient containerClient, Logger logger) {
        this.containerClient = containerClient;
        this.logger = logger;
    }

    private static BlobContainerClient buildContainerClient(Logger logger) {
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
        return client;
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

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            try (ByteArrayInputStream dataStream = new ByteArrayInputStream(bytes)) {
                blobClient.upload(dataStream, bytes.length, true);
                if (attempt > 1) {
                    logger.info("Uploaded blob " + blobName + " on attempt " + attempt + "/" + MAX_UPLOAD_ATTEMPTS);
                }
                return blobName;
            } catch (Exception e) {
                lastFailure = new RuntimeException(
                        "Failed to upload order blob: " + blobName
                                + " (attempt " + attempt + "/" + MAX_UPLOAD_ATTEMPTS + ")", e);
                logger.warning(lastFailure.getMessage() + ": " + e.getMessage());

                if (attempt < MAX_UPLOAD_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_BASE_DELAY_MILLIS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw lastFailure;
                    }
                }
            }
        }

        throw lastFailure;
    }
}
