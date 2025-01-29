package com.heroku.java;

import com.sforce.async.*;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import com.heroku.java.config.SalesforceClient;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SpringBootApplication
@Controller
@EnableAsync
public class BasicAPIAccessApplication {

    private static final Logger logger = LoggerFactory.getLogger(BasicAPIAccessApplication.class);

    @Autowired
    private SalesforceClient salesforceClient;

    @GetMapping("/")
    public String index(Map<String, Object> model) {
        // Store accounts for each connection
        Map<String, List<String>> connectionAccounts = new LinkedHashMap<>();
        // Fetch all connections
        Map<String, PartnerConnection> connections = salesforceClient.getConnections();
        for (String connectionName : connections.keySet()) {
            try {
                QueryResult result = connections.get(connectionName).query("SELECT Name, Id FROM Account ORDER BY Name");
                List<String> accounts = new ArrayList<>();
                Optional.ofNullable(result.getRecords())
                        .stream()
                        .flatMap(Arrays::stream) // Convert array to a Stream
                        .forEach(record -> {
                            String name = (String) record.getField("Name");
                            String id = (String) record.getField("Id");
                            accounts.add(name + " (ID: " + id + ")");
                        });
                connectionAccounts.put(connectionName, accounts);
            } catch (Exception e) {
                logger.error("Error retrieving accounts for connection {}: {}", connectionName, e.getMessage());
                connectionAccounts.put(connectionName, List.of("Error retrieving accounts: " + e.getMessage()));
            }
        }
        model.put("connectionAccounts", connectionAccounts);
        return "index";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startBulkDataInsertion() {
        Map<String, PartnerConnection> connections = salesforceClient.getConnections();
        if (!connections.containsKey("empty-org")) {
            logger.info("No 'empty-org' connection found. Skipping bulk account creation.");
            return;
        }
        // Check if "Bulk Account" records already exist
        PartnerConnection partnerConnection = connections.get("empty-org");
        try {
            String query = "SELECT Id FROM Account WHERE Name LIKE 'Bulk Account%' LIMIT 1";
            QueryResult result = partnerConnection.query(query);
            if (result.getRecords() != null && result.getSize() > 0) {
                logger.info("Existing 'Bulk Account' records found. Skipping bulk insert.");
                return;
            }
        } catch (ConnectionException e) {
            logger.error("Error checking for existing bulk accounts: {}", e.getMessage());
            return;
        }
        new Thread(() -> insertBulkAccounts(partnerConnection)).start();
    }

    @Async
    public void insertBulkAccounts(PartnerConnection partnerConnection) {
        try {
            logger.info("Starting Bulk API process for 'empty-org'");

            BulkConnection bulkConnection = getBulkConnection(partnerConnection);
            JobInfo job = new JobInfo();
            job.setObject("Account");
            job.setOperation(OperationEnum.insert);
            job.setContentType(ContentType.CSV);
            job = bulkConnection.createJob(job);
            logger.info("Created Bulk API job: {}", job.getId());

            StringWriter stringWriter = new StringWriter();
            try (CSVPrinter csvPrinter = new CSVPrinter(stringWriter, 
                    CSVFormat.DEFAULT.builder()
                            .setHeader("Name", "BillingStreet", "BillingCity", "BillingState", "BillingPostalCode", "BillingCountry")
                            .build())) {
                for (int i = 1; i <= 1000; i++) {
                    csvPrinter.printRecord(
                            "Bulk Account " + i,
                            "123 Main St Apt " + i,
                            "Sample City",
                            "CA",
                            "900" + (i % 100),
                            "USA"
                    );
                }
            }

            BatchInfo batch;
            try {
                batch = bulkConnection.createBatchFromStream(job, 
                    new ByteArrayInputStream(stringWriter.toString().getBytes(StandardCharsets.UTF_8)));
                logger.info("Submitted batch: {}", batch.getId());
            } catch (AsyncApiException e) {
                logger.error("Failed to create batch: {}", e.getMessage());
                return;
            }

            while (true) {
                try {
                    Thread.sleep(5000);
                    BatchInfo[] batchInfoList = bulkConnection.getBatchInfoList(job.getId()).getBatchInfo();
                    for (BatchInfo bi : batchInfoList) {
                        logger.info("Batch {} - State: {}", bi.getId(), bi.getState());
                        if (bi.getState() == BatchStateEnum.Completed || bi.getState() == BatchStateEnum.Failed) {
                            logger.info("Batch processing complete.");
                            return;
                        }
                    }
                } catch (AsyncApiException e) {
                    logger.error("Error fetching batch status: {}", e.getMessage());
                    return;
                }
            }

        } catch (Exception e) {
            logger.error("Error in Bulk API process: {}", e.getMessage());
        }
    }

    private BulkConnection getBulkConnection(PartnerConnection partnerConnection) throws ConnectionException, AsyncApiException {
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(partnerConnection.getSessionHeader().getSessionId());
        String instanceUrl = partnerConnection.getConfig().getServiceEndpoint();
        config.setRestEndpoint(instanceUrl.replace("/services/Soap/u/", "/services/async/"));
        return new BulkConnection(config);
    }

    public static void main(String[] args) {
        SpringApplication.run(BasicAPIAccessApplication.class, args);
    }
}
