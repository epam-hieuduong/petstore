# Azure Service Bus Deployment Steps (PetStoreOrderService <-> OrderItemsReserver)

Azure CLI commands to provision the Service Bus namespace and queue used to
decouple `petstoreorderservice` (publisher) from `petstoreorderitemsreserver`
(consumer, via Service Bus trigger - see `petstoreorderitemsreserver/AZURE_DEPLOYMENT.md`
for the Function App side). Run from PowerShell with Azure CLI logged in
(`az login`) and pointed at the right subscription (`az account set --subscription <id>`).

## 0. Variables

```powershell
$RG          = "petstore-rg"
$LOCATION    = "centralus"
$SB_NS       = "petstoreservicebus1234"          # 6-50 chars, globally unique
$SB_QUEUE    = "order-items-reservation"
```

## 1. Create the Service Bus namespace (Standard tier - required for DLQ + queues)

```powershell
az servicebus namespace create `
  --resource-group $RG `
  --name $SB_NS `
  --location $LOCATION `
  --sku Standard
```

## 2. Create the queue with a max delivery count of 3

`--max-delivery-count 3` means the platform will dead-letter a message after 3
failed delivery attempts if the function abandons/doesn't complete it - this
backs up the explicit in-code retry-then-dead-letter logic added in the
OrderItemsReserver function (see Phase 2/7 of the implementation plan).

```powershell
az servicebus queue create `
  --resource-group $RG `
  --namespace-name $SB_NS `
  --name $SB_QUEUE `
  --max-delivery-count 3 `
  --default-message-time-to-live P14D
```

## 3. Get the connection string

Use a namespace-level (or queue-scoped, for least privilege) shared access policy.

```powershell
$SB_CONN = az servicebus namespace authorization-rule keys list `
  --resource-group $RG `
  --namespace-name $SB_NS `
  --name RootManageSharedAccessKey `
  --query primaryConnectionString -o tsv
```

## 4. Configure petstoreorderservice (publisher) app settings

```powershell
az webapp config appsettings set `
  --resource-group $RG `
  --name petstore-orderservice `
  --settings `
    "SERVICEBUS_CONNECTION_STRING=$SB_CONN" `
    "SERVICEBUS_QUEUE_NAME=$SB_QUEUE"

az webapp restart --resource-group $RG --name petstore-orderservice
```

## 5. Configure petstoreorderitemsreserver (listener) app settings

The Function App uses the binding-level connection app setting
`SERVICEBUS_CONNECTION` (see `@ServiceBusQueueTrigger(connection = "SERVICEBUS_CONNECTION")`
in `OrderItemsReserverFunction`), plus the queue name.

```powershell
az functionapp config appsettings set `
  --resource-group $RG `
  --name demo-petstoreorderitemsreserver-westeurope-02 `
  --settings `
    "SERVICEBUS_CONNECTION=$SB_CONN" `
    "SERVICEBUS_QUEUE_NAME=$SB_QUEUE"

az webapp restart --resource-group $RG --name demo-petstoreorderitemsreserver-westeurope-02
```

## 6. Verify

```powershell
az servicebus queue show `
  --resource-group $RG `
  --namespace-name $SB_NS `
  --name $SB_QUEUE `
  --query "{status:status, maxDeliveryCount:maxDeliveryCountFromDeadLetteringOnMessageExpiration}"
```

Then update the cart from the PetStoreApp UI and confirm:
1. A message briefly appears/disappears from the `$SB_QUEUE` active message count (Portal > Service Bus namespace > Queue > Overview).
2. A blob named `order-<sessionId>.json` appears in the `orderitemsreserver` storage container (see `petstoreorderitemsreserver/AZURE_DEPLOYMENT.md`).

## Next steps

- Dead-letter monitoring + email fallback via Logic App: see
  `infra/LOGIC_APP_DEADLETTER_FALLBACK.md`.
