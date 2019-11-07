package com.netstream.ch.lab.crappy_crocodile.init.analysis;

/**
 * Created by Julian Hanhart (chdhaju0) on 07.11.19.
 */
public class Video {

    private String id;
    private String name;
    private String contentType;
    private String size;
    private String link;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("Video{");
        sb.append("id='").append(id).append('\'');
        sb.append(", name='").append(name).append('\'');
        sb.append(", contentType='").append(contentType).append('\'');
        sb.append(", size='").append(size).append('\'');
        sb.append(", link='").append(link).append('\'');
        sb.append('}');
        return sb.toString();
    }

}
