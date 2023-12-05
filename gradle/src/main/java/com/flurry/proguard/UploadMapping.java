/*
 * Copyright Yahoo Inc. 2016, see https://github.com/flurry/upload-clients/blob/master/LICENSE.txt for full details
 */
package com.flurry.proguard;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

public class UploadMapping {
    private static final String METADATA_BASE = "https://crash-metadata.flurry.com/pulse/v1";
    private static final String UPLOAD_BASE = "https://upload.flurry.com/upload/v1";
    public static final int FIVE_SECONDS_IN_MS = 5 * 1000;
    public static final int ONE_MINUTE_IN_MS = 60 * 1000;
    public static final int THREE_SECONDS_IN_MS = 3 * 1000;
    public static final int TEN_MINUTES_IN_MS = 10 * ONE_MINUTE_IN_MS;

    private static boolean EXIT_PROCESS_ON_ERROR = false;
    private static Logger LOGGER = LoggerFactory.getLogger(UploadMapping.class.getName());
    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
                .setConnectTimeout(FIVE_SECONDS_IN_MS) // 5 Seconds
                .setSocketTimeout(FIVE_SECONDS_IN_MS)
                .setConnectionRequestTimeout(ONE_MINUTE_IN_MS).build();
    private static CloseableHttpClient httpClient;

    public static void main(String[] args) throws IOException {
        ArgumentParser parser = ArgumentParsers.newArgumentParser("com.flurry.proguard.UploadMapping", true)
                .description("Uploads Proguard/Native Mapping Files for Android");
        parser.addArgument("-k", "--api-key").required(true)
                    .help("API Key for your project");
        parser.addArgument("-u", "--uuid").required(true)
                    .help("The build UUID");
        parser.addArgument("-p", "--path").required(true)
                .help("Path to ProGuard/Native mapping file for the build");
        parser.addArgument("-t", "--token").required(true)
                    .help("A Flurry auth token to use for the upload");
        parser.addArgument("-to", "--timeout").type(Integer.class).setDefault(TEN_MINUTES_IN_MS)
                    .help("How long to wait (in ms) for the upload to be processed");
        parser.addArgument("-n", "--ndk").type(Boolean.class).setDefault(false)
                    .help("Is it a Native mapping file");

        Namespace res = null;
        try {
            res = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }

        EXIT_PROCESS_ON_ERROR = true;
        if (res.getBoolean("ndk")) {
            uploadFiles(res.getString("api_key"), res.getString("uuid"),
                    new ArrayList<>(Collections.singletonList(res.getString("path"))),
                    res.getString("token"), res.getInt("timeout"), AndroidUploadType.ANDROID_NATIVE);
        } else {
            uploadFiles(res.getString("api_key"), res.getString("uuid"),
                    new ArrayList<>(Collections.singletonList(res.getString("path"))),
                    res.getString("token"), res.getInt("timeout"), AndroidUploadType.ANDROID_JAVA);
        }
    }
    public static void setLogger(Logger logger) {
        LOGGER = logger;
    }

    /**
     * Parses an properties file
     *
     * @param filePath the path to the config file
     * @return a map of the keys and values from the config
     */
    public static Properties parseConfigFile(String filePath) {
        Properties config = new Properties();
        File configFile = new File(filePath);
        try {
            config.load(new FileInputStream(configFile));
        } catch (IOException e) {
            failWithError("Bad config file {}", configFile.getAbsolutePath(), e);
        }

        return config;
    }

    /**
     * Tar a ProGuard/Native mapping file and send it to Flurry's crash service
     *
     * @param apiKey the API key for the project being built
     * @param uuid the uuid for this build
     * @param paths the paths to the ProGuard/Native mapping.txt files
     * @param token the auth token for API calls
     * @param timeout the amount of time to wait for the upload to be processed (in ms)
     * @param androidUploadType type of upload
     */
    public static void uploadFiles(String apiKey, String uuid, List<String> paths, String token, int timeout,
                                   AndroidUploadType androidUploadType) throws IOException {
        ArrayList<File> files = new ArrayList<>();
        paths.forEach(path -> {
            File file = new File(path);
            if (file.isDirectory()) {
                failWithError("{} is a directory. Please provide the path to " + androidUploadType.getDisplayName()
                        + " mapping file " + path);
            }
            files.add(file);
        });

        if (apiKey == null) {
            failWithError("No API key provided");
        }
        if (androidUploadType == AndroidUploadType.ANDROID_JAVA && uuid == null) {
            failWithError("No UUID provided");
        }
        if (token == null) {
            failWithError("No token provided");
        }

        try {
            httpClient = HttpClientBuilder.create().setDefaultRequestConfig(REQUEST_CONFIG).build();

            File zippedFile = createArchive(files, uuid);
            String projectId = lookUpProjectId(apiKey, token);
            LOGGER.info("Found project {} for api key {}", projectId, apiKey);

            String payload = getUploadJson(zippedFile, projectId, androidUploadType.getUploadType());
            String uploadId = createUpload(projectId, payload, token);
            LOGGER.info("Created upload with ID: {}", uploadId);

            sendToUploadService(zippedFile, projectId, uploadId, token);
            LOGGER.info(androidUploadType.getDisplayName() + " mapping uploaded to Flurry");

            waitForUploadToBeProcessed(projectId, uploadId, token, timeout);
            LOGGER.info("Upload completed successfully!");
        } finally {
            httpClient.close();
            httpClient = null;
        }
    }

    /**
     * Create a gzipped tar archive containing the ProGuard/Native mapping files
     *
     * @param files array of mapping.txt files
     * @return the tar-gzipped archive
     */
    private static File createArchive(List<File> files, String uuid) {
        try {
            File tarZippedFile = File.createTempFile("tar-zipped-file", ".tgz");
            TarArchiveOutputStream taos = new TarArchiveOutputStream(
                        new GZIPOutputStream(
                                    new BufferedOutputStream(
                                                new FileOutputStream(tarZippedFile))));
            for (File file : files) {
                taos.putArchiveEntry(new TarArchiveEntry(file,
                        (uuid != null && !uuid.isEmpty() ? uuid : UUID.randomUUID()) + ".txt"));
                IOUtils.copy(new FileInputStream(file), taos);
                taos.closeArchiveEntry();
            }
            taos.finish();
            taos.close();
            return tarZippedFile;
        } catch (IOException e) {
            failWithError("IO Exception while trying to tar and zip the file.", e);
            return null;
        }
    }

    /**
     * Call the metadata service to get the project's ID
     *
     * @param apiKey the API key for the project
     * @param token the Flurry auth token
     * @return the project's ID
     */
    private static String lookUpProjectId(String apiKey, String token) throws IOException {
        String queryUrl = String.format("%s/project?fields[project]=apiKey&filter[project.apiKey]=%s",
                    METADATA_BASE, apiKey);
        JSONObject jsonObject;
        try (CloseableHttpResponse response = executeHttpRequest(new HttpGet(queryUrl), getMetadataHeaders(token))) {
            expectStatus(response, HttpURLConnection.HTTP_OK);
            jsonObject = getJsonFromEntity(response.getEntity());
            JSONArray jsonArray = jsonObject.getJSONArray("data");
            if (jsonArray.length() == 0) {
                failWithError("No projects found for the API Key: " + apiKey);
            }
            return jsonArray.getJSONObject(0).get("id").toString();
        }
    }

    /**
     * Get the payload for creating the Upload in the metadata service
     *
     * @param zippedFile the archive to upload
     * @param projectId the project's ID
     * @return a JSON string to be sent to the metadata service
     */
    private static String getUploadJson(File zippedFile, String projectId, String uploadType) {
        return getUploadTemplate()
                    .replace("UPLOAD_TYPE", uploadType)
                    .replace("UPLOAD_SIZE", Long.toString(zippedFile.length()))
                    .replace("PROJECT_ID", projectId);
    }

    /**
     * Convert a HTTP response to JSON
     *
     * @param httpEntity the response body
     * @return a JSON object
     */
    private static JSONObject getJsonFromEntity(HttpEntity httpEntity) {
        try {
            return new JSONObject(EntityUtils.toString(httpEntity));
        } catch (IOException e) {
            failWithError("Cannot read HttpEntity {}", httpEntity, e);
            return null;
        } finally {
            EntityUtils.consumeQuietly(httpEntity);
        }
    }

    /**
     * Read the template Upload from resources
     *
     * @return a mostly complete JSON string
     */
    private static String getUploadTemplate() {
        return "{\"data\": {" +
                    "\"type\": \"upload\"," +
                    "\"attributes\":" +
                        "{\"uploadType\": \"UPLOAD_TYPE\", \"contentLength\": UPLOAD_SIZE}," +
                    "\"relationships\":" +
                        "{\"project\":{\"data\":{\"id\":PROJECT_ID,\"type\":\"project\"}}}" +
                    "}" +
                "}";
    }

    /**
     * Register this upload with the metadata service
     *
     * @param projectId the id of the project
     * @param payload the JSON body to send
     * @param token the Flurry auth token
     * @return the id of the created upload
     */
    private static String createUpload(String projectId, String payload, String token) throws IOException {
        String postUrl = String.format("%s/project/%s/uploads", METADATA_BASE, projectId);
        List<Header> requestHeaders = getMetadataHeaders(token);
        HttpPost postRequest = new HttpPost(postUrl);
        postRequest.setEntity(new StringEntity(payload, Charset.forName("UTF-8")));
        try (CloseableHttpResponse response = executeHttpRequest(postRequest, requestHeaders)) {
            expectStatus(response, HttpURLConnection.HTTP_CREATED);
            JSONObject jsonObject = getJsonFromEntity(response.getEntity());
            return jsonObject.getJSONObject("data").get("id").toString();
        } finally {
            postRequest.releaseConnection();
        }
    }

    /**
     * Upload the archive to Flurry
     *
     * @param file the archive to send
     * @param projectId the project's id
     * @param uploadId the the upload's id
     * @param token the Flurry auth token
     */
    private static void sendToUploadService(File file, String projectId, String uploadId, String token)
            throws IOException {
        String uploadServiceUrl = String.format("%s/upload/%s/%s", UPLOAD_BASE, projectId, uploadId);
        List<Header> requestHeaders = getUploadServiceHeaders(file.length(), token);
        HttpPost postRequest = new HttpPost(uploadServiceUrl);
        postRequest.setEntity(new FileEntity(file));
        try (CloseableHttpResponse response = executeHttpRequest(postRequest, requestHeaders)) {
            expectStatus(response, HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_ACCEPTED);
        } finally {
            postRequest.releaseConnection();
        }
    }

    /**
     * Ensure that a response had an expected status
     *
     * @param response the API response
     * @param validStatuses the list of acceptable statuses
     */
    private static void expectStatus(CloseableHttpResponse response, Integer... validStatuses) {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            failWithError("The provided token is expired");
        }
        if (!Arrays.asList(validStatuses).contains(statusCode)) {
            String responseString;
            try {
                responseString = "Response Body: " + EntityUtils.toString(response.getEntity());
            } catch (IOException e) {
                responseString = "IO Exception while reading the response body.";
            }
            failWithError("Request failed: {} {}", statusCode, responseString);
        }
    }

    /**
     * Query the metadata service to see if the upload was processed
     *
     * @param projectId the project's id
     * @param uploadId the upload's id
     * @param token the Flurry auth token
     * @param maxWaitTime how long to wait for the upload to be processes (in ms)
     */
    private static void waitForUploadToBeProcessed(String projectId, String uploadId, String token, int maxWaitTime)
            throws IOException {
        int waitingTime = 0;
        int maxTimeToWait = Math.max(ONE_MINUTE_IN_MS, maxWaitTime);
        int multiplier = 1;
        while (true) {
            JSONObject upload = fetchUpload(projectId, uploadId, token);
            String uploadStatus = upload.getJSONObject("data")
                        .getJSONObject("attributes")
                        .getString("uploadStatus").toUpperCase();
            switch (uploadStatus) {
                case "COMPLETED":
                    return;

                case "FAILED":
                    String reason = upload.getJSONObject("data")
                                .getJSONObject("attributes")
                                .getString("failureReason");
                    failWithError("Upload processing failed: {}", reason);

                default:
                    if (waitingTime < maxTimeToWait) {
                        multiplier = multiplier + 1;
                        int sleepTime = THREE_SECONDS_IN_MS * multiplier;
                        waitingTime += sleepTime;
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            failWithError("Exception while waiting for the upload to be processed", e);
                        }
                    } else {
                        failWithError("Upload not processed after {}s", maxTimeToWait / 1000);
                    }

            }
            LOGGER.debug("Upload still not processed after {}s", waitingTime / 1000);
        }
    }

    /**
     * Fetch the upload from the metadata service
     *
     * @param projectId the project's id
     * @param uploadId the upload's id
     * @param token the Flurry auth token
     * @return the upload
     */
    private static JSONObject fetchUpload(String projectId, String uploadId, String token) throws IOException {
        String queryUrl = String.format("%s/project/%s/uploads/%s?fields[upload]=uploadStatus,failureReason",
                    METADATA_BASE, projectId, uploadId);
        HttpGet getRequest = new HttpGet(queryUrl);
        List<Header> requestHeaders = getMetadataHeaders(token);
        try (CloseableHttpResponse response = executeHttpRequest(getRequest, requestHeaders)) {
            expectStatus(response, HttpURLConnection.HTTP_OK);
            return getJsonFromEntity(response.getEntity());
        } finally {
            getRequest.releaseConnection();
        }
    }

    private static CloseableHttpResponse executeHttpRequest(HttpUriRequest request, List<Header> requestHeaders) {
        for (Header header : requestHeaders) {
            request.setHeader(header.getName(), header.getValue());
        }
        try {
            return httpClient.execute(request);
        } catch (IOException e) {
            failWithError("IO Exception during request: {}", request, e);
            return null;
        }
    }

    /**
     * Get headers for a JSON API service
     *
     * @param token the Flurry auth token to use
     * @return the headers
     */
    private static List<Header> getMetadataHeaders(String token) {
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Authorization", "Bearer " + token));
        headers.add(new BasicHeader("Accept", "application/vnd.api+json"));
        headers.add(new BasicHeader("Content-Type", "application/vnd.api+json"));
        return headers;
    }

    /**
     * Get headers for the upload service
     *
     * @param size the size of the payload
     * @param token the Flurry auth token to use
     * @return the headers
     */
    private static List<Header> getUploadServiceHeaders(long size, String token) {
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Content-Type", "application/octet-stream"));
        headers.add(new BasicHeader("Range", "bytes 0-" + Long.toString(size - 1)));
        headers.add(new BasicHeader("Authorization", "Bearer " + token));
        return headers;
    }

    /**
     * Print a message and exit the script
     *
     * @param format The message format string
     * @param args the extra arguments for the logger
     */
    private static void failWithError(String format, Object... args) {
        LOGGER.error(format, args);
        if (EXIT_PROCESS_ON_ERROR) {
            System.exit(1);
        } else {
            String message = format;
            Throwable cause = null;
            if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
                cause = (Throwable) args[args.length - 1];
                args = Arrays.copyOf(args, args.length - 1);
            }
            if (args.length > 0) {
                message = String.format(format.replace("{}", "%s"), args);
            }
            throw new RuntimeException(message, cause);
        }
    }

    private static void validateResponse(CloseableHttpResponse httpResponse, String message) {
        if (httpResponse == null) {
            throw new NullPointerException(message);
        }
    }
}
