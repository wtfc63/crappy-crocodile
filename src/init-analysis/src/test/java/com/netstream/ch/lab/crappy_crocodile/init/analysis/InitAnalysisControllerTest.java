package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.RxHttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.annotation.MicronautTest;

@MicronautTest
public class InitAnalysisControllerTest {

    @Inject
    @Client("/")
    RxHttpClient client;

    @Test
    public void testInit() throws Exception {
        assertEquals(
                "test",
                client.retrieve(HttpRequest.POST("/", "{\"id\":\"test\"}"), Result.class)
                        .blockingFirst()
                        .getVideoId());
    }

}
