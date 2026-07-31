# OrderItemsReserver

An Azure Function (Java, Service Bus queue-triggered) that reserves order items for a PetStore customer session by uploading the order and product list as a JSON file to Azure Blob Storage. Runs and deploys as a container.

## Trigger

`reserveOrderItems` is triggered by messages on the Service Bus queue that `petstoreorderservice` publishes to on every cart update (see `petstoreorderservice`'s `ServiceBusPublisherService`). There is no HTTP endpoint anymore - PetStoreApp/PetStoreOrderService never call this Function directly.

Message body (JSON):
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

The blob uploaded to storage is named `order-<sessionId>.json` and is overwritten on every message for the same session.

If the Function throws (parse error, or all 3 blob upload retry attempts failing - see `BlobStorageService.uploadOrder`), the Service Bus message is left uncompleted and is redelivered. Once the queue's configured max delivery count is reached (`3`), Service Bus automatically dead-letters the message; a Logic App monitors the dead-letter queue and emails the manager (see `../infra/DEPLOYMENT_STEPS.md`, Part B).

## Configuration

| Env var | Description |
|---|---|
| `SERVICEBUS_CONNECTION` | Connection string for the Service Bus namespace to listen on |
| `SERVICEBUS_QUEUE_NAME` | Queue name to listen on (default: `order-items-reservation`) |
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
