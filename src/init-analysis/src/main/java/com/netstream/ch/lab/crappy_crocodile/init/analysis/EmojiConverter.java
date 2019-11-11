package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import com.google.cloud.videointelligence.v1p3beta1.Entity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
//import io.micronaut.http.HttpRequest;
//import io.micronaut.http.MediaType;
//import io.micronaut.http.client.RxHttpClient;
//import io.micronaut.http.client.annotation.Client;
//import io.micronaut.http.uri.UriBuilder;
//import io.reactivex.Maybe;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

//import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Julian Hanhart (chdhaju0) on 08.11.19.
 */
@Singleton
public class EmojiConverter {


    protected static final String ENDPOINT_BASE_URL = "https://www.emojidex.com/api/v1/";
    protected static final String RESOURCE_SEARCH = "/search/emoji";
    protected static final String FIELD_NAME_MOJI = "moji";
    protected static final String FIELD_NAME_EMOJI = "emoji";

    private Map<String, String> emojiMap = new HashMap<>();

//    @Client(ENDPOINT_BASE_URL)
//    @Inject
//    RxHttpClient httpClient;


    public String convertToEmoji(SceneEntity entity) {
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

    public String lookUp(String object) {
        //  Since the Micronaut HTTP client does not allow GET requests with a body,
        //  we'll have to use the Apache Client and misappropriate it a bit to use the Emojidex API (>_<)

        /* Won't work:
        Maybe<String> response = httpClient
                .retrieve(
                        HttpRequest
                                .GET(UriBuilder.of(RESOURCE_SEARCH).build())
                                .body("code_cont=" + object)
                                .contentType(MediaType.FORM)
                )
                .firstElement();
        JsonElement json = JsonParser.parseString(response.blockingGet());
        */

        try {
            HttpClient client = new DefaultHttpClient();
            HttpGetWithEntity get = new HttpGetWithEntity(ENDPOINT_BASE_URL + RESOURCE_SEARCH);
            final StringEntity entity = new StringEntity("code_cont=" + object);
            entity.setContentType(ContentType.APPLICATION_FORM_URLENCODED.getMimeType());
            get.setEntity(entity);
            HttpResponse response = client.execute(get);
            JsonElement json = JsonParser.parseString(IOUtils.toString(response.getEntity().getContent()));
            JsonArray emojis = json.getAsJsonObject().getAsJsonArray(FIELD_NAME_EMOJI);
            if ((emojis != null) && (emojis.size() > 0)) {
                for (JsonElement emoji : emojis) {
                    JsonObject entry = emoji.getAsJsonObject();
                    if (entry.has(FIELD_NAME_MOJI) && !entry.get(FIELD_NAME_MOJI).isJsonNull()) {
                        return entry.get(FIELD_NAME_MOJI).getAsString();
                    }
                }
            }
            return null;
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    /**
     * Manhandle the Apache HTTP Client to allow for GET calls with a body
     */
    public static class HttpGetWithEntity extends HttpEntityEnclosingRequestBase {

        public final static String GET_METHOD = "GET";

        public HttpGetWithEntity(final URI uri) {
            super();
            setURI(uri);
        }

        public HttpGetWithEntity(final String uri) {
            super();
            setURI(URI.create(uri));
        }

        @Override
        public String getMethod() {
            return GET_METHOD;
        }
    }

}
