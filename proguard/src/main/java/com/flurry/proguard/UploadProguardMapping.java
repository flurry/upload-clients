package com.flurry.proguard;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

public class UploadProguardMapping {

    private static final String METADATA_BASE = "https://crash-metadata.flurry.com/pulse/v1/";
    private static final String UPLOAD_BASE = "https://upload.flurry.com/upload/v1/upload/";
    private static final int MAX_DURATION_SECONDS_DEFAULT = 600; // 10 Minutes
    private static Logger LOGGER = Logger.getLogger(UploadProguardMapping.class.getName());
    private static HttpClient httpClient;

    public static void main(String[] args) {

        ArgumentParser parser = ArgumentParsers.newArgumentParser("com.flurry.proguard.UploadProguardMapping", true)
                .description("Uploads Proguard Mapping Files for Android");
        parser.addArgument("-k", "--api-key").help("API Key for your project").required(true);
        parser.addArgument("-u", "--uuid").help("UUID").required(true);
        parser.addArgument("-p", "--path").help("Path to Proguard file to be uploaded").required(true);
        parser.addArgument("-t", "--token").help("Provide a valid Access Token").required(true);
        parser.addArgument("-to", "--timeout").type(Integer.class).help(
                "Provide a timeout value for Upload.")
                .setDefault(MAX_DURATION_SECONDS_DEFAULT);

        Namespace res = null;
        try {
            res = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }

        uploadFile(res.getString("api_key"), res.getString("uuid"),
                res.getString("path"), res.getString("token"), res.getInt("timeout"));
    }

    public static void uploadFile(String apiKey, String uuid, String pathToFile, String token, int timeout, Logger logger) {
        LOGGER = logger;
        uploadFile(apiKey, uuid, pathToFile, token, timeout);
    }

    public static void uploadFile(String apiKey, String uuid, String pathToFile, String token, int timeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5 * 1000) // 5 Seconds
                .setSocketTimeout(5 * 1000)
                .build();
        httpClient = HttpClientBuilder
                .create()
                .setDefaultRequestConfig(requestConfig)
                .build();
        File file = new File(pathToFile);
        if (file.isDirectory()) {
            LOGGER.log(Level.SEVERE,
                    pathToFile + " is a directory. Please retry with path to the proguard file.");
            System.exit(1);
        }
        File zippedFile = tarZipFile(file, uuid);
        String projectId = lookUpProjectId(apiKey, token);
        String payload = getJson("/json/upload.json")
                .replace("UPLOAD_SIZE", Long.toString(zippedFile.length()))
                .replace("PROJECT_ID", projectId);
        String uploadId = createUpload(projectId, payload, token);
        LOGGER.log(Level.INFO, "Upload created with upload ID: " + uploadId);
        sendToUploadService(zippedFile, projectId, uploadId, token);
        String retval = checkUploadStatus(projectId, uploadId, token, timeout);
        if (retval != null) {
            LOGGER.log(Level.SEVERE, "Failed to upload with Failure Reason: " + retval);
            System.exit(1);
        }
        LOGGER.log(Level.INFO, "Upload Completed Successfully!");
    }

    private static File tarZipFile(File file, String uuid) {
        File tarZippedFile = null;
        try {
            tarZippedFile = File.createTempFile("tar-zipped-file", ".tar.gz");
            TarArchiveOutputStream taos = new TarArchiveOutputStream(new GZIPOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tarZippedFile))));
            taos.putArchiveEntry(new TarArchiveEntry(file, uuid + ".txt"));
            IOUtils.copy(new FileInputStream(file), taos);
            taos.closeArchiveEntry();
            taos.finish();
            taos.close();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IO Exception while trying to tar and zip the file.", e);
            System.exit(1);
        }
        return tarZippedFile;
    }

    private static String lookUpProjectId(String apiKey, String token) {
        String queryUrl = METADATA_BASE + "project?filter[project.apiKey]=" + apiKey + "&fields[project]=apiKey";
        HttpGet getRequest = new HttpGet(queryUrl);
        List<Header> requestHeaders = getDefaultHeaders(token);
        HttpResponse response = executeHttpRequest(getRequest, requestHeaders);
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != 200) {
            if (statusCode == 401) {
                LOGGER.log(Level.SEVERE, "Look up for project failed with Status Code: " + statusCode +
                        " due to Invalid/Expired token. Please check the token provided.");
                System.exit(1);
            }
            LOGGER.log(Level.SEVERE, "Look up for project failed with Status Code: " +
                    statusCode + " and Status Reason: " + response.getStatusLine().getReasonPhrase());
            try {
                LOGGER.log(Level.SEVERE, "Response Body: " + EntityUtils.toString(response.getEntity()));
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "IO Exception while reading the response body.");
            }
            System.exit(1);
        }
        JSONObject jsonObject = getJsonFromEntity(response.getEntity());
        JSONArray jsonArray = jsonObject.getJSONArray("data");
        if (jsonArray.length() == 0) {
            LOGGER.log(Level.SEVERE, "No projects found for the API Key: " + apiKey);
            System.exit(1);
        }
        return jsonArray.getJSONObject(0).get("id").toString();
    }

    private static JSONObject getJsonFromEntity(HttpEntity httpEntity) {
        JSONObject jsonObject = null;
        try {
            jsonObject = new JSONObject(EntityUtils.toString(httpEntity));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IO Exception while reading Json from HttpEntity", e);
            System.exit(1);
        }
        return jsonObject;
    }

    private static String getJson(String resourcePath) {
        InputStream is =  UploadProguardMapping.class.getResourceAsStream(resourcePath);
        String jsonString = null;
        try {
            jsonString = IOUtils.toString(is);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IO Exception while getting Json from " + resourcePath, e);
            System.exit(1);
        }
        return jsonString;
    }

    private static String createUpload(String projectId, String payload, String token) {
        String postUrl = METADATA_BASE + "project/" + projectId + "/uploads";
        HttpPost postRequest = new HttpPost(postUrl);
        List<Header> requestHeaders = getDefaultHeaders(token);
        try {
            postRequest.setEntity(new StringEntity(payload));
        } catch (UnsupportedEncodingException e) {
            LOGGER.log(Level.SEVERE, "Unsupported Encoding Exception while trying to create Upload", e);
            System.exit(1);
        }
        HttpResponse response = executeHttpRequest(postRequest, requestHeaders);
        if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_CREATED) {
            LOGGER.log(Level.SEVERE, "Failed to create Upload with Status Code: " +
                    response.getStatusLine().getStatusCode() + " and Status Reason: "
                    + response.getStatusLine().getReasonPhrase());
            try {
                LOGGER.log(Level.SEVERE, "Response Body: " + EntityUtils.toString(response.getEntity()));
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "IO Exception while reading the response body", e);
            }
            System.exit(1);
        }

        JSONObject jsonObject = getJsonFromEntity(response.getEntity());
        return jsonObject.getJSONObject("data").get("id").toString();
    }

    private static void sendToUploadService(File file, String projectId, String uploadId, String token) {
        String uploadServiceUrl = UPLOAD_BASE + projectId + "/" + uploadId;
        HttpPost postRequest = new HttpPost(uploadServiceUrl);
        List<Header> requestHeaders = getHeadersForUploadService(file.length(), token);
        postRequest.setEntity(new FileEntity(file));
        HttpResponse response = executeHttpRequest(postRequest, requestHeaders);
        int responseStatusCode = response.getStatusLine().getStatusCode();
        if (responseStatusCode != 201 && responseStatusCode != 202) {
            LOGGER.log(Level.SEVERE, "Upload Service POST failed with Status Code: " + responseStatusCode +
                    " and Status Reason: " + response.getStatusLine().getReasonPhrase());
            System.exit(1);
        }
    }

    public static String checkUploadStatus (String projectId, String uploadId, String token, int maxDurationSeconds) {
        String queryUrl = METADATA_BASE + "project/" + projectId + "/uploads/" +
                uploadId + "?fields[upload]=uploadStatus,failureReason";
        HttpGet getRequest = new HttpGet(queryUrl);
        List<Header> requestHeaders = getDefaultHeaders(token);
        HttpResponse response = executeHttpRequest(getRequest, requestHeaders);
        if (response.getStatusLine().getStatusCode() != 200) {
            LOGGER.log(Level.SEVERE, "Checking Upload Status failed with Status Code: " +
                    response.getStatusLine().getStatusCode() + " and Status Reason: " +
                    response.getStatusLine().getReasonPhrase());
            System.exit(1);
        }
        String uploadStatus =  getJsonFromEntity(response.getEntity()).getJSONObject("data")
                .getJSONObject("attributes").getString("uploadStatus");
        int waitingTime = 0;
        while (!uploadStatus.equals("COMPLETED") && !uploadStatus.equals("FAILED")) {
            LOGGER.log(Level.INFO, "Waiting for Upload to complete");
            if (waitingTime < maxDurationSeconds) {
                waitingTime += 5;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "Exception while waiting for the Upload to complete", e);
                    System.exit(1);
                }
                response = executeHttpRequest(getRequest, requestHeaders);
                uploadStatus =  getJsonFromEntity(response.getEntity()).getJSONObject("data")
                        .getJSONObject("attributes").getString("uploadStatus");
            } else {
                LOGGER.log(Level.SEVERE, "Timed out while checking for upload status.");
                System.exit(1);
            }
        }

        if (uploadStatus.equals("FAILED")) {
            return getJsonFromEntity(response.getEntity()).getJSONObject("data")
                    .getJSONObject("attributes").getString("failureReason");
        }
        return null;
    }

    private static HttpResponse executeHttpRequest(HttpUriRequest request, List<Header> requestHeaders) {
        for (Header header : requestHeaders) {
            request.setHeader(header.getName(), header.getValue());
        }
        HttpResponse httpResponse = null;
        try {
            httpResponse = httpClient.execute(request);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IO Exception while doing an Http Request", e);
            System.exit(1);
        }
        return httpResponse;
    }

    private static List<Header> getDefaultHeaders(String token) {
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Authorization", "Bearer " + token));
        headers.add(new BasicHeader("Accept", "application/vnd.api+json"));
        headers.add(new BasicHeader("Content-Type", "application/vnd.api+json"));
        return headers;
    }

    private static List<Header> getHeadersForUploadService(long size, String token) {
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("Content-Type", "application/octet-stream"));
        headers.add(new BasicHeader("Range", "bytes 0-" + Long.toString(size - 1)));
        headers.add(new BasicHeader("Authorization", "Bearer " + token));
        return headers;
    }
}
