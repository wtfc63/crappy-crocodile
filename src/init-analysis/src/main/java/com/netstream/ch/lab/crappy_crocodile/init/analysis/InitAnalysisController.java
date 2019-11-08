package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.videointelligence.v1p3beta1.AnnotateVideoProgress;
import com.google.cloud.videointelligence.v1p3beta1.AnnotateVideoRequest;
import com.google.cloud.videointelligence.v1p3beta1.AnnotateVideoResponse;
import com.google.cloud.videointelligence.v1p3beta1.Feature;
import com.google.cloud.videointelligence.v1p3beta1.VideoAnnotationResults;
import com.google.cloud.videointelligence.v1p3beta1.VideoIntelligenceServiceClient;
import com.google.gson.Gson;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Controller
public class InitAnalysisController {

    private static final Pattern GCS_URL_PATTERN =
            Pattern.compile("^gs:\\/\\/([a-z0-9\\-]+)\\/(\\w+\\/?)*(\\.[a-zA-Z0-9]+)*$");

    private final Gson gson = new Gson();

    @Post
    public HttpResponse<Result> initAnalysis(@Body PubSubBody body) {
        PubSubMessage message = body.getMessage();
        if (message == null) {
          String msg = "Bad Request: invalid Pub/Sub message format";
          System.out.println(msg);
          return HttpResponse.badRequest(new Result(null, msg));
        } else {
            Video video = gson.fromJson(
                    new String(Base64.decodeBase64(message.getData()), Charsets.UTF_8),
                    Video.class);
            try {
                SortedSet<Scene> scenes = initCloudIntel(video);
                return HttpResponse.ok(finalize(video, scenes));
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(e.getMessage());
                return HttpResponse.badRequest(new Result(video.getId(), e.getMessage()));
            }
        }
    }

    private SortedSet<Scene> initCloudIntel(Video video) throws Exception {
        try (VideoIntelligenceServiceClient client = VideoIntelligenceServiceClient.create()) {
            // Create an operation that will contain the response when the operation completes.
            AnnotateVideoRequest request = AnnotateVideoRequest.newBuilder()
                    .setInputUri(video.getUrl())
                    .addFeatures(Feature.SHOT_CHANGE_DETECTION)
                    .addFeatures(Feature.LABEL_DETECTION)
                    .addFeatures(Feature.EXPLICIT_CONTENT_DETECTION)
                    .build();

            OperationFuture<AnnotateVideoResponse, AnnotateVideoProgress> response =
                    client.annotateVideoAsync(request);
            System.out.println("Waiting for operation to complete...");

            List<VideoAnnotationResults> results = response.get().getAnnotationResultsList();
            if (results.isEmpty()) {
                String message = "Could not detect anything in " + video.getId();
                System.out.println(message);
                throw new Exception(message);
            } else {
                SortedSet<Scene> scenes = new TreeSet<>();
                for (VideoAnnotationResults result : results) {
                    scenes.addAll(Scene.from(result.getShotLabelAnnotationsList()));
                    result.getExplicitAnnotation().getFramesList().forEach(f -> {
                        scenes.forEach(s -> s.updateExplicitContentLikelihoodIfContains(f));
                    });
                }
                return scenes;
            }
        }
    }

    private Result finalize(Video video, SortedSet<Scene> scenes) {
        if ((video != null) && (scenes != null)) {
            final Storage gcs = StorageOptions.getDefaultInstance().getService();
            final String processingBucketName = getBucketName(video.getUrl());
            if (processingBucketName != null) {
                final Bucket processingBucket = gcs.get(
                        processingBucketName,
                        Storage.BucketGetOption.fields(Storage.BucketField.values()));
                if (processingBucket != null) {
                    String textTrackContent = exportTextTrack(scenes, true);
                    BlobId blobId = BlobId.of(
                            processingBucketName, getBlobName(video, "objects.vtt"));
                    BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                            .setContentType("text/vtt")
                            .build();
                    Blob blob = gcs.create(blobInfo, textTrackContent.getBytes(StandardCharsets.UTF_8));
                    return new Result(video.getId(), blob.getSelfLink());
                } else {
                    return new Result(
                            video.getId(),
                            "Could not get Bucket '" + processingBucketName + "'");
                }
            } else {
                return new Result(
                        video.getId(),
                        "Could not deduct Bucket Name from '" + video.getUrl() + "'");
            }
        } else {
            return new Result(
                    (video != null) ? video.getId() : null,
                    "Either the video info or the scenes were null");
        }
    }

    private String getBucketName(String objectUrl) {
        final Matcher matcher = GCS_URL_PATTERN.matcher(objectUrl);
        if (matcher.matches()) {
            return (matcher.groupCount() > 1) ? matcher.group(1) : null;
        } else {
            return null;
        }
    }

    private String getBlobName(String objectUrl) {
        final String bucketName = getBucketName(objectUrl);
        if (bucketName != null) {
            return objectUrl.substring(("gs://" + bucketName + "/").length());
        } else {
            return null;
        }
    }

    private String getBlobName(Video video, String filename) {
        return String.format("%s/%s", video.getId(), filename);
    }

    private String exportTextTrack(SortedSet<Scene> scenes, boolean includeCategories) {
        if (scenes != null) {
            return scenes.stream()
                    .map(s -> s.toTextTrackLine(includeCategories))
                    .collect(Collectors.joining("\n"));
        } else {
            return null;
        }
    }

}