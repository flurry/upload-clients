package com.flurry.proguard;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class UploadProguardMapping {

    private static final String METADATA_BASE = "https://crash-metadata.flurry.com/pulse/v1/";
    private static final String UPLOAD_BASE = "https://upload.flurry.com/upload/v1/upload/";
    private static HttpClient httpClient;

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 3) {
            System.out.println("Please provide 3 arguments in the following order:");
            System.out.println("ApiKey PathToProguard AccessToken");
            throw new IOException("Invalid number of arguments");
        }
        httpClient = HttpClientBuilder.create().build();
        new UploadProguardMapping().uploadFile(args[0], args[1], args[2]);
    }

    public void uploadFile(String apiKey, String pathToFiles, String token)
            throws IOException, InterruptedException {
        File file = new File(pathToFiles);
        File zippedFile = tarZipFile(file);
        String projectId = lookUpProjectId(apiKey, token);
        String payload = getJson("/json/upload.json").replace("UPLOAD_SIZE",
                Long.toString(zippedFile.length()))
                .replace("PROJECT_ID", projectId);
        String uploadId = createUpload(projectId, payload, token);
        System.out.println("Upload created with upload ID: " + uploadId);
        sendToUploadService(zippedFile, projectId, uploadId, token);
        String retval = checkUploadStatus(projectId, uploadId, token);
        if (retval != null) {
            throw new IOException("Failed to upload with Failure Reason: " + retval);
        }
        System.out.println("SUCCESS!!");
    }

    private File tarZipFile(File file) throws IOException {
        File tarZippedFile = File.createTempFile("tar-zipped-file", ".tar.gz");
        TarArchiveOutputStream taos = new TarArchiveOutputStream(new GZIPOutputStream(new BufferedOutputStream(
                new FileOutputStream(tarZippedFile))));
        putArchives(taos, file, "");
        taos.finish();
        taos.close();
        return tarZippedFile;
    }

    private void putArchives(TarArchiveOutputStream taos, File file, String base) throws IOException {
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                putArchives(taos, f, base + file.getName() + "/");
            }
        } else {
            // file is just a single file
            taos.putArchiveEntry(new TarArchiveEntry(file, base + file.getName()));
            IOUtils.copy(new FileInputStream(file), taos);
            taos.closeArchiveEntry();
        }
    }

    private String lookUpProjectId(String apiKey, String token) throws IOException {
        String queryUrl = METADATA_BASE + "project?filter[project.apiKey]=" + apiKey + "&fields[project]=apiKey";
        HttpGet getRequest = new HttpGet(queryUrl);
        Map<String, String> requestHeaders = getDefaultHeaders(token);
        for (String key : requestHeaders.keySet()) {
            getRequest.addHeader(key, requestHeaders.get(key));
        }
        HttpResponse response = httpClient.execute(getRequest);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Look up for project failed with Status Code: " +
                    response.getStatusLine().getStatusCode() + " and Status Reason: " +
                    response.getStatusLine().getReasonPhrase());
        }
        JSONObject jsonObject = getJsonFromEntity(response.getEntity());
        System.out.println("JSON Object: " + jsonObject);
        JSONArray jsonArray = jsonObject.getJSONArray("data");
        if (jsonArray.length() == 0) {
            throw new IOException("No projects found for the API Key: " + apiKey);
            // throw error
        }
        return jsonArray.getJSONObject(0).get("id").toString();
    }

    private JSONObject getJsonFromEntity(HttpEntity httpEntity) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(httpEntity.getContent()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            sb.append(line + '\n');
        }
        bufferedReader.close();
        return new JSONObject(sb.toString());
    }

    private String getJson(String resourcePath) throws IOException {
        InputStream is =  this.getClass().getResourceAsStream(resourcePath);
        return IOUtils.toString(is);
    }

    private String createUpload(String projectId, String payload, String token)
            throws IOException {
        String postUrl = METADATA_BASE + "project/" + projectId + "/uploads";
        HttpPost postRequest = new HttpPost(postUrl);
        Map<String, String> requestHeaders = getDefaultHeaders(token);
        for (String key : requestHeaders.keySet()) {
            postRequest.setHeader(key, requestHeaders.get(key));
        }
        postRequest.setEntity(new StringEntity(payload));
        HttpResponse response = httpClient.execute(postRequest);
        if (response.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_CREATED) {
            throw new IOException("Failed to create Upload with Status Code: " +
                    response.getStatusLine().getStatusCode() + " and Status Reason: "
                    + response.getStatusLine().getReasonPhrase());
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
        String input;
        StringBuilder resp = new StringBuilder();
        while ((input = in.readLine()) != null) {
            resp.append(input);
        }
        in.close();
        JSONObject jsonObject = new JSONObject(resp.toString());
        return jsonObject.getJSONObject("data").get("id").toString();
    }

    private void sendToUploadService(File file, String projectId, String uploadId, String token)
            throws IOException {
        String uploadServiceUrl = UPLOAD_BASE + projectId + "/" + uploadId;
        HttpPost postRequest = new HttpPost(uploadServiceUrl);
        Map<String, String> requestHeaders = getHeadersForUploadService(file.length(), token);
        for (String key : requestHeaders.keySet()) {
            postRequest.setHeader(key, requestHeaders.get(key));
        }
        postRequest.setEntity(new FileEntity(file));
        HttpResponse response = httpClient.execute(postRequest);
        int responseStatusCode = response.getStatusLine().getStatusCode();
        if (responseStatusCode != 201 && responseStatusCode != 202) {
            throw new IOException("Upload Service POST failed with Status Code: " + responseStatusCode +
                            " and Status Reason: " + response.getStatusLine().getReasonPhrase());
        }
    }

    public String checkUploadStatus (String projectId, String uploadId, String token)
            throws IOException, InterruptedException {
        String queryUrl = METADATA_BASE + "project/" + projectId + "/uploads/" +
                uploadId + "?fields[upload]=uploadStatus,failureReason";
        HttpGet getRequest = new HttpGet(queryUrl);
        Map<String, String> requestHeaders = getDefaultHeaders(token);
        for (String key : requestHeaders.keySet()) {
            getRequest.setHeader(key, requestHeaders.get(key));
        }
        HttpResponse response = httpClient.execute(getRequest);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Checking Upload Status failed with Status Code: " +
                    response.getStatusLine().getStatusCode() + " and Status Reason: " +
                    response.getStatusLine().getReasonPhrase());
        }
        String uploadStatus =  getJsonFromEntity(response.getEntity()).getJSONObject("data")
                .getJSONObject("attributes").getString("uploadStatus");
        while (!uploadStatus.equals("COMPLETED") && !uploadStatus.equals("FAILED")) {
            Thread.sleep(5000);
            response = httpClient.execute(getRequest);
            uploadStatus =  getJsonFromEntity(response.getEntity()).getJSONObject("data")
                    .getJSONObject("attributes").getString("uploadStatus");
        }

        if (uploadStatus.equals("FAILED")) {
            return getJsonFromEntity(response.getEntity()).getJSONObject("data")
                    .getJSONObject("attributes").getString("failureReason");
        }
        return null;
    }

    private Map<String, String> getDefaultHeaders(String token) {
        Map<String, String> headersMap = new HashMap<>();
        headersMap.put("Authorization", "Bearer " + token);
        headersMap.put("Accept", "application/vnd.api+json");
        headersMap.put("Content-Type", "application/vnd.api+json");
        return headersMap;
    }

    private Map<String, String> getHeadersForUploadService(long size, String token) {
        Map<String, String> headersMap = new HashMap<>();
        headersMap.put("Content-Type", "application/octet-stream");
        headersMap.put("Range", "bytes 0-" + Long.toString(size - 1));
        headersMap.put("Authorization", "Bearer " + token);
        return headersMap;
    }
}
