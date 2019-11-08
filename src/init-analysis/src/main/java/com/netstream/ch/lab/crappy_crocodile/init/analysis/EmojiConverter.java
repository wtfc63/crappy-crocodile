package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.cloud.videointelligence.v1p3beta1.Entity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Julian Hanhart (chdhaju0) on 08.11.19.
 */
public class EmojiConverter {


    private static Map<String, String> emojiMap = new HashMap<>();


    public static String convertToEmoji(SceneEntity entity) {
        if (entity != null) {
            String emoji = emojiMap.get(entity.getEntity().getDescription());
            if (emoji != null) {
                return emoji;
            } else {
                emoji = lookUp(entity.getEntity().getDescription());
                if (emoji != null) {
                    emojiMap.put(entity.getEntity().getDescription(), emoji);
                    return emoji;
                } else {
                    for (Entity categoryEntity : entity.getCategoryEntities()) {
                        if (emoji != null) {
                            emoji = lookUp(categoryEntity.getDescription());
                        }
                    }
                    if (emoji != null) {
                        emojiMap.put(entity.getEntity().getDescription(), emoji);
                        return emoji;
                    } else {
                        return "🤷";
                    }
                }
            }
        } else {
            return null;
        }
    }

    private static String lookUp(String object) {
        try {
            HttpClient client = new DefaultHttpClient();
            MyHttpGetWithEntity e = new MyHttpGetWithEntity("https://www.emojidex.com/api/v1/search/emoji");
            e.setEntity(new StringEntity("code_cont=" + object));
            HttpResponse response = client.execute(e);
            JsonElement json = JsonParser.parseString(IOUtils.toString(response.getEntity().getContent()));
            JsonArray emojis = json.getAsJsonObject().getAsJsonArray("emoji");
            if ((emojis != null) && (emojis.size() > 0)) {
                return emojis.get(0).getAsJsonObject().getAsJsonPrimitive("moji").getAsString();
            } else {
                return null;
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    public static class MyHttpGetWithEntity extends HttpEntityEnclosingRequestBase {

        public final static String GET_METHOD = "GET";

        public MyHttpGetWithEntity(final URI uri) {
            super();
            setURI(uri);
        }

        public MyHttpGetWithEntity(final String uri) {
            super();
            setURI(URI.create(uri));
        }

        @Override
        public String getMethod() {
            return GET_METHOD;
        }
    }

}
