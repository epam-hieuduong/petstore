package com.chtrembl.petstoreorderitemsreserver.model;

/**
 * Response payload returned to PetStoreApp after attempting a reservation upload.
 */
public class ReservationResult {
    private boolean success;
    private String blobName;
    private String message;

    public ReservationResult() {
    }

    public ReservationResult(boolean success, String blobName, String message) {
        this.success = success;
        this.blobName = blobName;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getBlobName() {
        return blobName;
    }

    public void setBlobName(String blobName) {
        this.blobName = blobName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
