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

/**
 * Main Spring Boot application class that demonstrates Salesforce API access
 * through Heroku AppLink. This application showcases:
 * <ul>
 *   <li>Querying Account records from multiple Salesforce orgs</li>
 *   <li>Bulk API operations for inserting large volumes of Account records</li>
 * </ul>
 * 
 * <p>The application automatically starts a bulk data insertion process on startup
 * if an 'empty-org' connection is configured and no existing bulk accounts are found.
 * 
 * @author Heroku
 * @version 1.0
 */
@SpringBootApplication
@Controller
@EnableAsync
public class BasicAPIAccessApplication {

    /** Logger instance for this class. */
    private static final Logger logger = LoggerFactory.getLogger(BasicAPIAccessApplication.class);

    /** Salesforce client for managing connections to multiple Salesforce orgs. */
    @Autowired
    private SalesforceClient salesforceClient;

    /**
     * Handles the root web request and displays Account records from all configured
     * Salesforce connections.
     * 
     * <p>Queries each connected Salesforce org for Account records and formats them
     * for display in the view template.
     * 
     * @param model the Spring MVC model map to populate with account data
     * @return the view name "index" to render the accounts page
     */
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

    /**
     * Event listener that triggers bulk data insertion when the application is ready.
     * 
     * <p>This method checks for an 'empty-org' connection and verifies if bulk accounts
     * already exist. If no bulk accounts are found, it starts an asynchronous process
     * to insert 1000 sample Account records using the Salesforce Bulk API.
     * 
     * <p>The method will skip bulk insertion if:
     * <ul>
     *   <li>No 'empty-org' connection is configured</li>
     *   <li>Bulk Account records already exist in the org</li>
     * </ul>
     */
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

    /**
     * Asynchronously inserts 1000 Account records into Salesforce using the Bulk API.
     * 
     * <p>This method:
     * <ol>
     *   <li>Creates a Bulk API job for Account insertions</li>
     *   <li>Generates CSV data for 1000 sample accounts</li>
     *   <li>Submits a batch to the Bulk API</li>
     *   <li>Polls for batch completion status</li>
     *   <li>Closes the job once processing is complete</li>
     * </ol>
     * 
     * <p>The job is automatically closed when:
     * <ul>
     *   <li>Batch processing completes successfully</li>
     *   <li>Batch processing fails</li>
     *   <li>An error occurs during batch creation or status checking</li>
     * </ul>
     * 
     * @param partnerConnection the Salesforce PartnerConnection to use for the bulk operation
     */
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
                // Closing the batch job
                try {
                    bulkConnection.closeJob(job.getId());
                    logger.info("Closed job: {}", job.getId());
                } catch (AsyncApiException closeException) {
                    logger.error("Failed to close job: {}", closeException.getMessage());
                }
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
                            // Closing the batch job
                            try {
                                bulkConnection.closeJob(job.getId());
                                logger.info("Closed job: {}", job.getId());
                            } catch (AsyncApiException e) {
                                logger.error("Failed to close job: {}", e.getMessage());
                            }
                            return;
                        }
                    }
                } catch (AsyncApiException e) {
                    logger.error("Error fetching batch status: {}", e.getMessage());
                    // Closing the batch job
                    try {
                        bulkConnection.closeJob(job.getId());
                        logger.info("Closed job: {}", job.getId());
                    } catch (AsyncApiException closeException) {
                        logger.error("Failed to close job: {}", closeException.getMessage());
                    }
                    return;
                }
            }

        } catch (Exception e) {
            logger.error("Error in Bulk API process: {}", e.getMessage());
        }
    }

    /**
     * Creates a BulkConnection from a PartnerConnection by extracting session
     * information and converting the SOAP endpoint to the async REST endpoint.
     * 
     * @param partnerConnection the PartnerConnection to derive the BulkConnection from
     * @return a configured BulkConnection instance
     * @throws ConnectionException if there's an error accessing connection configuration
     * @throws AsyncApiException if there's an error creating the BulkConnection
     */
    private BulkConnection getBulkConnection(PartnerConnection partnerConnection) throws ConnectionException, AsyncApiException {
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(partnerConnection.getSessionHeader().getSessionId());
        String instanceUrl = partnerConnection.getConfig().getServiceEndpoint();
        config.setRestEndpoint(instanceUrl.replace("/services/Soap/u/", "/services/async/"));
        return new BulkConnection(config);
    }

    /**
     * Main entry point for the Spring Boot application.
     * 
     * @param args command-line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(BasicAPIAccessApplication.class, args);
    }
}
