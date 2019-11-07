package com.netstream.ch.lab.crappy_crocodile.init.analysis;

/**
 * Created by Julian Hanhart (chdhaju0) on 07.11.19.
 */
public class PubSubMessage {

    private String messageId;
    private String publishTime;
    private String data;

    public PubSubMessage() {}

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getPublishTime() {
        return publishTime;
    }

    public void setPublishTime(String publishTime) {
        this.publishTime = publishTime;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

}
