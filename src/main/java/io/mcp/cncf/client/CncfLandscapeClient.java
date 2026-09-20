package io.mcp.cncf.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST Client for CNCF Landscape Data API.
 *
 * <p>This client provides access to CNCF Cloud Native Landscape data
 * through their public full JSON data file. The CNCF Landscape publishes
 * comprehensive project data including GitHub metrics, maturity levels,
 * categories, and adoption information.</p>
 *
 * <p><strong>Data Source:</strong></p>
 * <ul>
 *   <li>Real-time data from https://landscape.cncf.io/data/full.json</li>
 *   <li>Includes all CNCF projects with actual GitHub metrics</li>
 *   <li>Current maturity levels and project metadata</li>
 *   <li>Company adoption and end user case studies</li>
 * </ul>
 *
 * @see <a href="https://landscape.cncf.io/data/full.json">CNCF Landscape Full Data JSON</a>
 * @see <a href="https://landscape.cncf.io/">CNCF Cloud Native Landscape</a>
 */
@RegisterRestClient(configKey = "cncf-landscape-api")
public interface CncfLandscapeClient {

    /**
     * Retrieves the complete CNCF Landscape data.
     * This contains all projects, categories, and metadata in a single JSON file.
     *
     * @return Full CNCF landscape data as JSON string
     */
    // The configured base URL is the site root (quarkus.rest-client.cncf-landscape-api.url),
    // so the path must carry /data: with "/full.json" alone the single-page app answered
    // its index.html with a 200, and every refresh failed on the first '<'.
    @GET
    @Path("/data/full.json")
    @Produces(MediaType.APPLICATION_JSON)
    String getFullLandscapeData();
}