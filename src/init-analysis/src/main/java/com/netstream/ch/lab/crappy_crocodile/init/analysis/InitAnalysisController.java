package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.gson.Gson;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import org.apache.commons.codec.CharEncoding;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;

import java.util.Arrays;

@Controller
public class InitAnalysisController {

    private final Gson gson = new Gson();

    @Post
    public HttpResponse<Result> initAnalysis(@Body PubSubBody body) {
        Result result = new Result();
        PubSubMessage message = body.getMessage();
        if (message == null) {
          String msg = "Bad Request: invalid Pub/Sub message format";
          System.out.println(msg);
          result.setMessage(msg);
          return HttpResponse.badRequest(result);
        } else {
            Video video = gson.fromJson(
                    new String(Base64.decodeBase64(message.getData()), Charsets.UTF_8),
                    Video.class);
            result.setVideoId(video.getId());
            result.setMessage(video.toString());
            return HttpResponse.ok(result);
        }
    }

}