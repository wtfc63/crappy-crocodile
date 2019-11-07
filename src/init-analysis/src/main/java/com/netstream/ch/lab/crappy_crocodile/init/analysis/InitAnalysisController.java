package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;

@Controller
public class InitAnalysisController {

    @Post(consumes = MediaType.APPLICATION_JSON)
    public Result initAnalysis(@Body Video video) {
        Result msg = new Result();
        msg.setVideoId(video.getId());
        msg.setMessage("Video ID: " + video.getId());
        return msg;
    }
}