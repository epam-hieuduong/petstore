# Logic App Fallback: Dead-Letter Queue Monitoring + Email Notification

When `petstoreorderitemsreserver`'s `reserveOrderItems` function exhausts its
3 in-process upload retries (see `BlobStorageService.uploadOrder`) and throws,
the Service Bus message is left uncompleted and redelivered. Once the queue's
`max-delivery-count` (set to `3` in `infra/SERVICE_BUS_DEPLOYMENT.md`) is
reached, Azure Service Bus automatically moves the message to the queue's
dead-letter sub-queue (`order-items-reservation/$DeadLetterQueue`).

This Logic App watches that dead-letter sub-queue and emails a manager so the
order can be reserved manually.

The Service Bus connector's dead-letter trigger/actions use a SAS connection
string (no interactive consent needed), but the email connector (Outlook.com
or Office 365) requires a one-time interactive sign-in, so this workflow is
built primarily via the **Logic App Designer in the Azure Portal** rather than
pure CLI/ARM (consistent with this repo's "no IaC authored" approach).

## Prerequisites

- Service Bus namespace + `order-items-reservation` queue already created
  with `--max-delivery-count 3` (`infra/SERVICE_BUS_DEPLOYMENT.md` steps 1-2).
- A mailbox to send notifications from: an Outlook.com/Microsoft account, or
  an Office 365 account. Either works with the corresponding connector.

## 1. Create the Logic App (Consumption) shell

```powershell
$RG      = "petstore-rg"
$LOCATION = "centralus"
$LOGICAPP_NAME = "petstore-orderitemsreserver-dlq-fallback"

az logic workflow create `
  --resource-group $RG `
  --location $LOCATION `
  --name $LOGICAPP_NAME `
  --definition '{"$schema":"https://schema.management.azure.com/providers/Microsoft.Logic/schemas/2016-06-01/workflowdefinition.json#","contentVersion":"1.0.0.0","triggers":{},"actions":{},"outputs":{}}'
```

This creates an empty Consumption Logic App. The trigger/actions below are
added via the Portal designer because the Service Bus and email connectors
need an API connection resource (and, for email, interactive OAuth consent).

## 2. Open the Designer and add the Service Bus trigger

1. Azure Portal > the `$LOGICAPP_NAME` Logic App > **Logic app designer**.
2. Add trigger: search for **Service Bus** > **When a message is received in
   a queue (peek-lock)**.
3. Create the connection:
   - **Connection name**: `petstore-servicebus-connection`
   - **Connection string**: the Service Bus namespace connection string from
     `infra/SERVICE_BUS_DEPLOYMENT.md` step 3 (`$SB_CONN`), or a scoped SAS
     policy with `Listen` rights on the queue for least privilege.
4. Trigger parameters:
   - **Queue name**: `order-items-reservation`
   - **Queue type**: switch to **Dead-letter queue** (this targets
     `order-items-reservation/$DeadLetterQueue`)
   - **Interval**: `1` **Frequency**: `Minute` (polling interval; lower for
     faster notification, higher to reduce cost)

## 3. Parse the dead-lettered order + failure reason

The trigger's `ContentData` output is the original order JSON (base64-encoded
by the connector, auto-decoded when referenced as a string). Add an action:

1. Add action: **Data Operations > Compose** (or **Parse JSON** with the
   `Order` schema below) - input: `@{triggerBody()?['ContentData']}`.

   Parse JSON schema (matches `petstoreorderservice`'s published `Order`):
   ```json
   {
     "type": "object",
     "properties": {
       "id": { "type": "string" },
       "email": { "type": "string" },
       "complete": { "type": "boolean" },
       "products": {
         "type": "array",
         "items": {
           "type": "object",
           "properties": {
             "id": { "type": "integer" },
             "name": { "type": "string" },
             "quantity": { "type": "integer" }
           }
         }
       }
     }
   }
   ```

2. The dead-letter reason/description are trigger message properties, available as:
   - `@{triggerBody()?['DeadLetterReason']}`
   - `@{triggerBody()?['DeadLetterErrorDescription']}`

## 4. Add the "Send an email" action

1. Add action: search **Outlook.com** (personal Microsoft account) or
   **Office 365 Outlook** (work/school account) > **Send an email (V2)**.
2. Sign in to create the connection (one-time interactive OAuth consent).
3. Configure:
   - **To**: the manager's email address (e.g. store in a Logic App parameter
     or hardcode for this exercise)
   - **Subject**: `Order reservation failed - session @{body('Parse_JSON')?['id']}`
   - **Body** (example, using dynamic content from the Parse JSON step):
     ```
     The OrderItemsReserver function could not reserve items for this order
     after 3 retry attempts and the message was dead-lettered.

     Session ID: @{body('Parse_JSON')?['id']}
     Customer email: @{body('Parse_JSON')?['email']}
     Complete: @{body('Parse_JSON')?['complete']}
     Products: @{body('Parse_JSON')?['products']}

     Dead-letter reason: @{triggerBody()?['DeadLetterReason']}
     Dead-letter description: @{triggerBody()?['DeadLetterErrorDescription']}

     Please reserve these items manually.
     ```

## 5. Complete the dead-lettered message

Add a final action so the message doesn't stay locked/reprocessed:

1. Add action: **Service Bus > Complete the message in a queue**.
2. Use the same connection as step 2.
3. Parameters:
   - **Queue name**: `order-items-reservation`
   - **Queue type**: **Dead-letter queue**
   - **Lock token**: `@{triggerBody()?['LockToken']}`

## 6. Save and test

Save the workflow, then trigger it end-to-end:

- Easiest: temporarily set the Service Bus queue's `max-delivery-count` to `1`
  and send a message that will fail (e.g. stop the reserver's storage account
  access, or send a malformed payload) so it dead-letters quickly; revert
  `max-delivery-count` to `3` afterwards.
- Or manually dead-letter a test message via the Portal's Service Bus
  Explorer (Queue > Service Bus Explorer > peek a message > Dead-letter).

Confirm: the manager's inbox receives the email with the order id, product
list, and dead-letter reason, and the message no longer appears in the
dead-letter queue after the run completes.
