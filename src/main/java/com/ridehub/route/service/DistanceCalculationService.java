package com.ridehub.route.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehub.route.config.ApplicationProperties;
import com.ridehub.route.service.dto.DistanceCalculationRequestDTO;
import com.ridehub.route.service.dto.DistanceCalculationResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Service for calculating distances between addresses using GraphHopper API.
 */
@Service
public class DistanceCalculationService {

    private static final Logger LOG = LoggerFactory.getLogger(DistanceCalculationService.class);
    private static final String CACHE_KEY_PREFIX = "distance:";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ApplicationProperties applicationProperties;

    public DistanceCalculationService(ApplicationProperties applicationProperties) {
        this.applicationProperties = applicationProperties;
        this.objectMapper = new ObjectMapper();
        this.webClient = createWebClient();
    }

    private WebClient createWebClient() {
        String apiKey = applicationProperties.getGraphHopper().getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            LOG.warn("GraphHopper API key is not configured. Distance calculation via GraphHopper will be disabled.");
            return null;
        }

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(applicationProperties.getGraphHopper().getBaseUrl())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024)); // 1MB

        LOG.info("GraphHopper API client initialized with base URL: {}",
                applicationProperties.getGraphHopper().getBaseUrl());

        return builder.build();
    }

    /**
     * Calculate distance between two addresses.
     *
     * @param requestDTO the distance calculation request
     * @return the distance calculation response
     */
    @Cacheable(value = "distanceCalculations")
    public DistanceCalculationResponseDTO calculateDistance(DistanceCalculationRequestDTO requestDTO) {
        LOG.info("Calculating distance from '{}' to '{}' using {} profile",
                requestDTO.getOriginAddress(),
                requestDTO.getDestinationAddress(),
                requestDTO.getVehicleProfile());

        try {
            // Geocode addresses to coordinates first
            String[] originCoords = geocodeAddress(requestDTO.getOriginAddress());
            String[] destCoords = geocodeAddress(requestDTO.getDestinationAddress());

            if (originCoords == null || destCoords == null) {
                return createErrorResponse("GEOCODING_FAILED", "Failed to geocode one or both addresses");
            }

            String url = String.format("/route?key=%s&point=%s,%s&point=%s,%s&vehicle=%s&locale=%s&points_encoded=%s&instructions=false&calc_points=false",
                    applicationProperties.getGraphHopper().getApiKey(),
                    originCoords[0], originCoords[1],
                    destCoords[0], destCoords[1],
                    requestDTO.getVehicleProfile().name().toLowerCase(),
                    requestDTO.getLanguage(),
                    requestDTO.getPointsEncoded());

            Mono<String> responseMono = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class);

            String responseBody = responseMono.block(Duration.ofMillis(
                    applicationProperties.getGraphHopper().getReadTimeout()));

            return parseGraphHopperResponse(responseBody);

        } catch (Exception e) {
            LOG.error("Error during distance calculation", e);
            return createErrorResponse("API_ERROR", "Distance calculation failed: " + e.getMessage());
        }
    }

    private String urlEncodeAddress(String address) {
        return java.net.URLEncoder.encode(address, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Geocode an address to coordinates using GraphHopper Geocoding API.
     *
     * @param address the address to geocode
     * @return array containing [latitude, longitude] or null if geocoding fails
     */
    private String[] geocodeAddress(String address) {
        try {
            String encodedAddress = urlEncodeAddress(address);
            String url = String.format("/geocode?key=%s&q=%s&locale=%s&limit=1",
                    applicationProperties.getGraphHopper().getApiKey(),
                    encodedAddress,
                    applicationProperties.getGraphHopper().getLocale());

            Mono<String> responseMono = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class);

            String responseBody = responseMono.block(Duration.ofSeconds(5));

            JsonNode root = objectMapper.readTree(responseBody);

            // Check for geocoding errors
            if (root.has("message")) {
                LOG.error("Geocoding error for address '{}': {}", address, root.get("message").asText());
                return null;
            }

            // Extract coordinates from the first result
            if (root.has("hits") && root.get("hits").isArray() && root.get("hits").size() > 0) {
                JsonNode firstHit = root.get("hits").get(0);
                String lat = firstHit.get("point").get("lat").asText();
                String lon = firstHit.get("point").get("lng").asText();

                LOG.debug("Geocoded '{}' to coordinates: {}, {}", address, lat, lon);
                return new String[]{lat, lon};
            } else {
                LOG.warn("No geocoding results found for address: {}", address);
                return null;
            }

        } catch (Exception e) {
            LOG.error("Error during geocoding for address: {}", address, e);
            return null;
        }
    }

    private DistanceCalculationResponseDTO parseGraphHopperResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            DistanceCalculationResponseDTO response = new DistanceCalculationResponseDTO();
            response.setCalculatedAt(Instant.now());

            // Check for GraphHopper errors
            if (root.has("message")) {
                response.setStatus("ERROR");
                response.setErrorMessage(root.get("message").asText());
                return response;
            }

            response.setStatus("OK");

            if (root.has("paths") && root.get("paths").isArray() && root.get("paths").size() > 0) {
                JsonNode path = root.get("paths").get(0);

                DistanceCalculationResponseDTO.DistanceElement element =
                    new DistanceCalculationResponseDTO.DistanceElement();
                element.setStatus("OK");

                // Extract distance
                if (path.has("distance")) {
                    DistanceCalculationResponseDTO.Distance distance =
                        new DistanceCalculationResponseDTO.Distance();
                    double distanceInMeters = path.get("distance").asDouble();
                    distance.setValue(BigDecimal.valueOf(distanceInMeters));
                    distance.setText(formatDistance(distanceInMeters));
                    element.setDistance(distance);
                }

                // Extract time
                if (path.has("time")) {
                    DistanceCalculationResponseDTO.Duration duration =
                        new DistanceCalculationResponseDTO.Duration();
                    long timeInMillis = path.get("time").asLong();
                    duration.setValue((int) (timeInMillis / 1000)); // Convert to seconds
                    duration.setText(formatDuration((int) (timeInMillis / 1000)));
                    element.setDuration(duration);
                }

                response.setElements(java.util.List.of(element));
            } else {
                response.setStatus("NO_ROUTE_FOUND");
                response.setErrorMessage("No route found between the specified points");
                response.setElements(java.util.List.of());
            }

            return response;

        } catch (IOException e) {
            LOG.error("Error parsing GraphHopper response", e);
            return createErrorResponse("PARSE_ERROR", "Failed to parse API response: " + e.getMessage());
        }
    }

    private String formatDistance(double distanceInMeters) {
        if (distanceInMeters >= 1000) {
            double km = distanceInMeters / 1000.0;
            return String.format("%.1f km", km);
        } else {
            return String.format("%.0f m", distanceInMeters);
        }
    }

    private String formatDuration(int durationInSeconds) {
        int hours = durationInSeconds / 3600;
        int minutes = (durationInSeconds % 3600) / 60;

        if (hours > 0) {
            return String.format("%d hours %d mins", hours, minutes);
        } else {
            return String.format("%d mins", minutes);
        }
    }

    private DistanceCalculationResponseDTO createErrorResponse(String status, String errorMessage) {
        DistanceCalculationResponseDTO response = new DistanceCalculationResponseDTO();
        response.setCalculatedAt(Instant.now());
        response.setStatus(status);
        response.setErrorMessage(errorMessage);
        response.setElements(java.util.List.of());
        return response;
    }

    /**
     * Generate a cache key for the distance calculation request.
     */
    public static String generateCacheKey(DistanceCalculationRequestDTO request) {
        return CACHE_KEY_PREFIX +
               String.format("%s|%s|%s|%s|%s|%s",
                       request.getOriginAddress().toLowerCase().trim(),
                       request.getDestinationAddress().toLowerCase().trim(),
                       request.getVehicleProfile(),
                       request.getLanguage(),
                       request.getEnableElevation(),
                       request.getPointsEncoded());
    }

    /**
     * Validate if the service is properly configured.
     */
    public boolean isConfigured() {
        return applicationProperties.getGraphHopper() != null &&
               applicationProperties.getGraphHopper().getApiKey() != null &&
               !applicationProperties.getGraphHopper().getApiKey().trim().isEmpty();
    }
}