package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class Result {

    private String videoId;
    private String message;

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    /**
     * @return the message
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message the message to set
     */
    public void setMessage(String message) {
        this.message = message;
    }

}