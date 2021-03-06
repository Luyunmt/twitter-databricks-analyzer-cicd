/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.eventhubs.checkstatus;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubConsumerAsyncClient;
import com.azure.messaging.eventhubs.models.EventPosition;
import com.azure.messaging.eventhubs.models.PartitionContext;

import org.json.*;

import reactor.core.Disposable;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ReceiveByDateTime {

    public static void main(String[] args)
            throws ExecutionException, InterruptedException, IOException {
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime argStartTime = null;

        if (args.length == 0) {
            System.out.println("Please provide a start date as the first argument");
            System.exit(1);
        }

        try {
            argStartTime = LocalDateTime.parse(args[0], formatter);
            System.out.println("Searching events after: " + argStartTime);
        } catch (Exception e) {
            System.out.println("There was an exception: " + e.toString());
            System.exit(1);
        }

        final LocalDateTime startTime = argStartTime;

        String namespaceName = System.getenv("EVENTHUB_NAMESPACE");
        String eventHubName = System.getenv("EVENTHUB_ALERTS");
        String sasKeyName = System.getenv("EVENTHUB_KEY_NAME");
        String sasKey = System.getenv("EVENTHUB_KEY");

        if (namespaceName == null || namespaceName.isEmpty()) {
            System.out.println("Please make sure to set EVENTHUB_NAMESPACE");
            System.exit(1);
        }
        if (eventHubName == null || eventHubName.isEmpty()) {
            System.out.println("Please make sure to set EVENTHUB_ALERTS");
            System.exit(1);
        }
        if (sasKeyName == null || sasKeyName.isEmpty()) {
            System.out.println("Please make sure to set EVENTHUB_KEY_NAME");
            System.exit(1);
        }
        if (sasKey == null || sasKey.isEmpty()) {
            System.out.println("Please make sure to set EVENTHUB_KEY");
            System.exit(1);
        }

        final String connStr = String.format("Endpoint=sb://%s.servicebus.windows.net/;", namespaceName) +
                               String.format("EntityPath=%s;", eventHubName) +
                               String.format("SharedAccessKeyName=RootManageSharedAccessKey;", sasKeyName) +
                               String.format("SharedAccessKey=${eventhub_key}", sasKey);
       
        EventHubConsumerAsyncClient consumer = new EventHubClientBuilder()
            .connectionString(connStr)
            .consumerGroup(EventHubClientBuilder.DEFAULT_CONSUMER_GROUP_NAME)
            .buildAsyncConsumerClient();

        Duration applicationDuration = Duration.ofMinutes(15);

        Disposable subscription  = consumer.getPartitionIds()
            .take(1)
            .flatMap(partitionId -> consumer.receiveFromPartition(partitionId, EventPosition.earliest()))
            .take(applicationDuration)
            .subscribe(partitionEvent -> {
                    EventData event = partitionEvent.getData();
                    PartitionContext partitionContext = partitionEvent.getPartitionContext();
        
                    System.out.print(String.format("[%s] Offset: %s, #: %s, Time: %s, PT: %s, ",
                    new java.util.Date(), event.getOffset(),
                    event.getSequenceNumber(),
                    event.getEnqueuedTime(), partitionContext.getPartitionId()));
                    if (event.getBody() != null) {
        
                        try {
                            final String dataString = new String(event.getBody(), UTF_8);
                            final JSONObject obj = new JSONObject(dataString);
                            final String windowStartString = obj.getString("windowStart");
                            final LocalDateTime eventDateTime = LocalDateTime.parse(windowStartString,
                                    formatter);
                            System.out.println("Event Time: " + windowStartString);
        
                            if (eventDateTime.isAfter(startTime)) {
                                System.out.println("Found a processed alert: " + dataString);
                                System.exit(0);
                            }
                        } catch (Exception e) {
                            System.out.println("There was a problem parsing the date of the event: "
                                    + event.getBody());
                        }
                    }           
                }, error -> {
                    // onError
                    System.err.println("Error occurred while consuming events: " + error);
                }, () -> {
                    // onComplete
                    System.out.println("Finished reading events.");
                });

        try {
            // Sleep the application so it does not end. This is because the .subscribe() is a non-blocking
            // call. While the thread is sleeping, the subscription will run in the background, processing 
            // messages.
            Thread.sleep(applicationDuration.toMillis());
        } catch (InterruptedException e) {
            System.err.println("Unable to sleep: " + e);
        } 
        
            finally {
            // Dispose and close of all the resources we've created.
            subscription.dispose();
            consumer.close();
        }
    } 
}
