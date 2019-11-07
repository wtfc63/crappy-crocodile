package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.videointelligence.v1p3beta1.*;
import com.google.gson.Gson;
import com.google.protobuf.Duration;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Controller
public class InitAnalysisController {

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
                Result result = initCloudIntel(video);
                return HttpResponse.ok(result);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(e.getMessage());
                return HttpResponse.badRequest(new Result(video.getId(), e.getMessage()));
            }
        }
    }

    private Result initCloudIntel(Video video) throws Exception {
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
                return new Result(video.getId(), message);
            } else {
                List<String> scenes = new ArrayList<>();
                for (VideoAnnotationResults result : results) {
//                    System.out.println("Labels:");
                    // get video segment label annotations
//                    for (LabelAnnotation annotation : result.getSegmentLabelAnnotationsList()) {
//                        System.out
//                                .println("Video label description : " + annotation.getEntity().getDescription());
//                        // categories
//                        for (Entity categoryEntity : annotation.getCategoryEntitiesList()) {
//                            System.out.println("Label Category description : " + categoryEntity.getDescription());
//                        }
//                        // segments
//                        for (LabelSegment segment : annotation.getSegmentsList()) {
//                            double startTime = segment.getSegment().getStartTimeOffset().getSeconds()
//                                    + segment.getSegment().getStartTimeOffset().getNanos() / 1e9;
//                            double endTime = segment.getSegment().getEndTimeOffset().getSeconds()
//                                    + segment.getSegment().getEndTimeOffset().getNanos() / 1e9;
//                            System.out.printf("Segment location : %.3f:%.3f\n", startTime, endTime);
//                            System.out.println("Confidence : " + segment.getConfidence());
//                        }
//                    }
//                    System.out.println("Explicit frames: " + result.getExplicitAnnotation().getFramesList());
                    scenes.addAll(
                            result.getShotLabelAnnotationsList().stream()
                                    .map(a -> {
                                        Duration startOffset = a.getSegments(0).getSegment().getStartTimeOffset();
                                        Duration endOffset = a.getSegments(0).getSegment().getEndTimeOffset();
                                        String object = a.getEntity().getDescription();
                                        return String.format(
                                                "%s --> %s\n%s\n \n",
                                                LocalTime.ofSecondOfDay(startOffset.getSeconds())
                                                        .withNano(startOffset.getNanos())
                                                        .format(DateTimeFormatter.BASIC_ISO_DATE),
                                                LocalTime.ofSecondOfDay(endOffset.getSeconds())
                                                        .withNano(endOffset.getNanos())
                                                        .format(DateTimeFormatter.BASIC_ISO_DATE),
                                                object);
                                    })
                                    .collect(Collectors.toList()));
                }
                System.out.println("Scenes\n" + scenes);
                return new Result(video.getId(), scenes.toString());
            }
        }
    }

}