package com.heroku.java;

import com.sforce.soap.partner.QueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import com.heroku.java.config.SalesforceClient;

import java.util.*;

@SpringBootApplication
@Controller
public class BasicAPIAccessApplication {

    @Autowired
    private SalesforceClient salesforceClient;

    @GetMapping("/")
    public String index(Map<String, Object> model) {
        // Store accounts for each connection
        Map<String, List<String>> connectionAccounts = new LinkedHashMap<>();
        // Fetch all connections
        Map<String, com.sforce.soap.partner.PartnerConnection> connections = salesforceClient.getConnections();
        for (String connectionName : connections.keySet()) {
            try {
                QueryResult result = connections.get(connectionName).query("SELECT Name, Id FROM Account");
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
                connectionAccounts.put(connectionName, List.of("Error retrieving accounts: " + e.getMessage()));
            }
        }
        model.put("connectionAccounts", connectionAccounts);
        return "index";
    }

    public static void main(String[] args) {
        SpringApplication.run(BasicAPIAccessApplication.class, args);
    }
}
