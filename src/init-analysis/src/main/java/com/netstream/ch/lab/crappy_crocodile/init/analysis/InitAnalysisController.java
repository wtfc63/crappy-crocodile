package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.videointelligence.v1p3beta1.AnnotateVideoProgress;
import com.google.cloud.videointelligence.v1p3beta1.AnnotateVideoRequest;
import com.google.cloud.videointelligence.v1p3beta1.AnnotateVideoResponse;
import com.google.cloud.videointelligence.v1p3beta1.Feature;
import com.google.cloud.videointelligence.v1p3beta1.VideoAnnotationResults;
import com.google.cloud.videointelligence.v1p3beta1.VideoIntelligenceServiceClient;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;

import javax.inject.Inject;
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

    protected static final String FILE_NAME_METADATA = "metadata.json";
    protected static final String FILE_NAME_TEST_TRACK = "objects.vtt";
    protected static final String FILE_NAME_EMOJI_TRACK = "emoji.vtt";
    protected static final String FILE_NAME_LOCK = "vis.lock";

    private final Gson gson = new Gson();
    private final Storage gcs = StorageOptions.getDefaultInstance().getService();

    @Inject
    private EmojiConverter emojiConverter;

    @Post
    public HttpResponse<Result> initAnalysis(@Body PubSubBody body) {
        PubSubMessage message = body.getMessage();
        if (message == null) {
          String msg = "Bad Request: invalid Pub/Sub message format";
          System.out.println(msg);
          return HttpResponse.badRequest(new Result(null, msg));
        } else {
            final Video video = gson.fromJson(
                    new String(Base64.decodeBase64(message.getData()), Charsets.UTF_8),
                    Video.class);
            try {
                final String bucketName = getBucketName(video.getUrl());
                final String lockBlobName = getBlobName(video, FILE_NAME_LOCK);
                Blob lockFile = gcs.get(bucketName, lockBlobName);
                if ((lockFile == null) || !lockFile.exists()) {
                    lockFile = createBlob(
                            getBucketName(video.getUrl()),
                            lockBlobName, video.getId().getBytes(), "text/plain");
                    // TODO Publish the "processing-started" event to Pub/Sub
                    final SortedSet<Scene> scenes = initCloudIntel(video);
                    Result result = exportTextTracks(video, scenes);
                    // TODO Publish the "processing-completed" event to Pub/Sub

                    result = finalize(video, result, lockFile);
                    // TODO Move finalize() to separate Cloud Run service (subscribed to the "processing-completed" event)

                    return HttpResponse.ok(result);
                } else {
                    return HttpResponse.ok(new Result(
                            video.getId(),
                            "Processing was already started by another instance"));
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(e.getMessage());
                return HttpResponse.badRequest(new Result(video.getId(), e.getMessage()));
            }
        }
    }

    @Post("/test/converter/{entity}")
    public HttpResponse<String> testConverter(@PathVariable String entity) {
        final String emoji = emojiConverter.lookUp(entity);
        return (emoji != null) ? HttpResponse.ok(emoji) : HttpResponse.notFound();
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

    private Result exportTextTracks(Video video, SortedSet<Scene> scenes) {
        if ((video != null) && (scenes != null)) {
            final String processingBucketName = getBucketName(video.getUrl());
            if (processingBucketName != null) {
                String textTrackContent = exportTextTrack(scenes, true);
                Blob blob = createBlob(
                        processingBucketName,
                        getBlobName(video, FILE_NAME_TEST_TRACK),
                        textTrackContent.getBytes(StandardCharsets.UTF_8),
                        "text/vtt");
                System.out.println("Created text track: " + blob.getName());

                textTrackContent = exportEmojiTrack(scenes);
                blob = createBlob(
                        processingBucketName,
                        getBlobName(video, FILE_NAME_EMOJI_TRACK),
                        textTrackContent.getBytes(StandardCharsets.UTF_8),
                        "text/vtt");
                System.out.println("Created emoji track: " + blob.getName());

                return new Result(video.getId(), blob.getSelfLink());
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

    private Result finalize(Video video, Result result, Blob lockFile) {
        if ((video != null) && (result != null)) {
            final String processingBucketName = getBucketName(video.getUrl());
            if (processingBucketName != null) {
                final String outputBucketName = getOutputBucketName(processingBucketName);

                Blob blob = moveBlob(
                        processingBucketName, outputBucketName,
                        getBlobName(video, getVideoFileName(video)));
                System.out.println(
                        "Moved video file to the output Bucket: " +
                                outputBucketName + "/" + blob.getName());

                blob = moveBlob(
                        processingBucketName, outputBucketName,
                        getBlobName(video, FILE_NAME_METADATA));
                System.out.println(
                        "Moved metadata file to the output Bucket: " +
                                outputBucketName + "/" + blob.getName());

                blob = moveBlob(
                        processingBucketName, outputBucketName,
                        getBlobName(video, FILE_NAME_TEST_TRACK));
                System.out.println(
                        "Moved text track to the output Bucket: " +
                                outputBucketName + "/" + blob.getName());

                blob = moveBlob(
                        processingBucketName, outputBucketName,
                        getBlobName(video, FILE_NAME_EMOJI_TRACK));
                System.out.println(
                        "Moved emoji track to the output Bucket: " +
                                outputBucketName + "/" + blob.getName());

                lockFile.delete();
                gcs.delete(processingBucketName, video.getId() + "/");
                return new Result(video.getId(), blob.getSelfLink());
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

    private String getOutputBucketName(String processingBucketName) {
        return processingBucketName.substring(0, processingBucketName.lastIndexOf('-')) + "-output";
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

    private String getVideoFileName(Video video) {
        final String extension = video.getContentType().substring(
                video.getContentType().lastIndexOf('/') + 1);
        return "video." + extension;
    }

    private Blob createBlob(String bucketName, String blobName, byte[] content, String contentType) {
        final BlobInfo blobInfo = BlobInfo
                .newBuilder(BlobId.of(bucketName, blobName))
                .setContentType(contentType)
                .build();
        return gcs.create(blobInfo, content);
    }

    private Blob moveBlob(String sourceBucketName, String targetBucketName, String blobName) {
        final Blob sourceBlob = gcs.get(sourceBucketName, blobName);
        final BlobInfo targetBlobInfo = BlobInfo
                .newBuilder(BlobId.of(targetBucketName, blobName))
                .setContentType(sourceBlob.getContentType())
                .build();
        final Blob targetBlob = gcs
                .copy(Storage.CopyRequest.of(sourceBucketName, blobName, targetBlobInfo))
                .getResult();
        gcs.delete(sourceBucketName, blobName);
        return targetBlob;
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

    private String exportEmojiTrack(SortedSet<Scene> scenes) {
        if (scenes != null) {
            return scenes.stream()
                    .map(s -> s.toEmojiTrackLine(emojiConverter))
                    .collect(Collectors.joining("\n"));
        } else {
            return null;
        }
    }

}