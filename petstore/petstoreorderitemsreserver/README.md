# OrderItemsReserver

An Azure Function (Java, HTTP-triggered) that reserves order items for a PetStore customer session by uploading the order and product list as a JSON file to Azure Blob Storage. Runs and deploys as a container.

## Endpoint

`POST /api/reserveOrderItems`

Request body (JSON):
```json
{
  "id": "<sessionId>",
  "email": "user@example.com",
  "complete": false,
  "products": [
    { "id": 1, "quantity": 2 }
  ]
}
```

The blob uploaded to storage is named `order-<sessionId>.json` and is overwritten on every call for the same session.

## Configuration

| Env var | Description |
|---|---|
| `BLOB_STORAGE_CONNECTION_STRING` | Connection string for the target Azure Storage Account |
| `BLOB_STORAGE_CONTAINER_NAME` | Blob container name (default: `orderitemsreserver`) |

## Local run

```
mvn clean package
mvn azure-functions:run
```

## Container build

```
docker build -t petstoreorderitemsreserver .
docker run -p 8085:80 --env-file ../.env petstoreorderitemsreserver
```
