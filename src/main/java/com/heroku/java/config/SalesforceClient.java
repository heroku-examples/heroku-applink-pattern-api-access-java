package com.heroku.java.config;

import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring component that manages Salesforce connections through Heroku AppLink.
 * 
 * <p>This class initializes and maintains PartnerConnection instances for multiple
 * Salesforce orgs configured via environment variables. It uses the Heroku AppLink
 * Integration API to retrieve authentication tokens and connection details.
 * 
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code HEROKU_APPLINK_API_URL} - The Heroku AppLink Integration API URL</li>
 *   <li>{@code HEROKU_APPLINK_TOKEN} - The authentication token for AppLink API</li>
 *   <li>{@code CONNECTION_NAMES} - Comma-separated list of connection names</li>
 *   <li>{@code HEROKU_APP_ID} - The Heroku application UUID</li>
 * </ul>
 * 
 * @author Heroku
 * @version 1.0
 */
@Component
public class SalesforceClient {

    /** The Heroku AppLink Integration API URL. */
    @Value("${HEROKU_APPLINK_API_URL:#{null}}")
    private String integrationApiUrl;

    /** The authentication token for Heroku AppLink API invocations. */
    @Value("${HEROKU_APPLINK_TOKEN:#{null}}")
    private String invocationsToken;

    /** Comma-separated list of Salesforce connection names to initialize. */
    @Value("${CONNECTION_NAMES:#{null}}")
    private String connectionNames;

    /** The Heroku application UUID for AppLink API requests. */
    @Value("${HEROKU_APP_ID:#{null}}")
    private String herokuAppId;

    /** Map of connection names to their corresponding PartnerConnection instances. */
    private final Map<String, PartnerConnection> connections = new HashMap<>();

    /**
     * Initializes Salesforce connections after component construction.
     * 
     * <p>This method is called automatically by Spring after dependency injection.
     * It parses the {@code CONNECTION_NAMES} environment variable and creates
     * PartnerConnection instances for each specified connection name.
     * 
     * @throws IllegalStateException if required environment variables are not set
     * @throws RuntimeException if connection initialization fails for any connection
     */
    @PostConstruct
    public void initializeConnections() {
        if (integrationApiUrl == null || invocationsToken == null || connectionNames == null) {
            throw new IllegalStateException("Heroku Integration environment variables are not set properly.");
        }
        String[] connectionNameArray = connectionNames.split(",");
        for (String connectionName : connectionNameArray) {
            connectionName = connectionName.trim();
            if (!connectionName.isEmpty()) {
                try {
                    connections.put(connectionName, createConnection(connectionName));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to initialize connection for: " + connectionName, e);
                }
            }
        }
    }

    /**
     * Creates a PartnerConnection for a specific Salesforce org using Heroku AppLink.
     * 
     * <p>This method:
     * <ol>
     *   <li>Makes a REST call to the Heroku AppLink API to retrieve authentication details</li>
     *   <li>Extracts the access token, API version, and instance URL from the response</li>
     *   <li>Configures and returns a PartnerConnection instance</li>
     * </ol>
     * 
     * @param developerName the connection name (developer name) configured in Heroku AppLink
     * @return a configured PartnerConnection instance for the specified org
     * @throws IllegalArgumentException if the developer name is null or empty
     * @throws IllegalStateException if the AppLink API returns an invalid response
     * @throws ConnectionException if there's an error creating the PartnerConnection
     */
    private PartnerConnection createConnection(String developerName) throws ConnectionException {
        if (developerName == null || developerName.isEmpty()) {
            throw new IllegalArgumentException("Developer name not provided");
        }
        
        // Prepare headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + invocationsToken);
        headers.set("X-App-UUID", herokuAppId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<?> entity = new HttpEntity<>(headers);

        // Make the REST call to get auth details
        String authUrl = integrationApiUrl + "/authorizations/" + developerName;
        ResponseEntity<Map<String, Object>> response = new RestTemplate().exchange(
            authUrl, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {}
        );
        
        if (response.getBody() == null || !response.getBody().containsKey("org")) {
            throw new IllegalStateException("Invalid response from Heroku AppLink API.");
        }

        // Extract org data from nested response structure
        @SuppressWarnings("unchecked")
        Map<String, Object> org = (Map<String, Object>) response.getBody().get("org");
        @SuppressWarnings("unchecked")
        Map<String, Object> userAuth = (Map<String, Object>) org.get("user_auth");

        // Retrieve authentication details
        String accessToken = (String) userAuth.get("access_token");
        String apiVersion = (String) org.get("api_version");
        String orgDomainUrl = (String) org.get("instance_url");

        // Configure and create Salesforce PartnerConnection
        ConnectorConfig config = new ConnectorConfig();
        String cleanApiVersion = apiVersion.replaceFirst("^v", "");
        config.setServiceEndpoint(orgDomainUrl + "/services/Soap/u/" + cleanApiVersion);
        config.setSessionId(accessToken);
        return new PartnerConnection(config);
    }

    /**
     * Retrieves a PartnerConnection for a specific connection name.
     * 
     * @param connectionName the name of the connection to retrieve
     * @return the PartnerConnection instance for the specified connection
     * @throws IllegalArgumentException if no connection exists for the given name
     */
    public PartnerConnection getConnection(String connectionName) {
        if (!connections.containsKey(connectionName)) {
            throw new IllegalArgumentException("No Salesforce PartnerConnection found for: " + connectionName);
        }
        return connections.get(connectionName);
    }

    /**
     * Returns a copy of all initialized Salesforce connections.
     * 
     * <p>The returned map is a defensive copy, so modifications to it will not
     * affect the internal connection map.
     * 
     * @return a map of connection names to their PartnerConnection instances
     */
    public Map<String, PartnerConnection> getConnections() {
        return new HashMap<>(connections);
    }
}
