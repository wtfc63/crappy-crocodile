package com.netstream.ch.lab.crappy_crocodile.init.analysis;

import io.micronaut.context.env.Environment;
import io.micronaut.runtime.Micronaut;

public class Application {

    public static void main(String[] args) {
        Micronaut.build(args)
                 .deduceEnvironment(false)
                 .environments(Environment.GOOGLE_COMPUTE)
                 .start();
    }
}