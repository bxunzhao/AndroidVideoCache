package com.danikula.videocache.sample;

public enum Video {

    ORANGE_1("https://premium.wallstcn.com/8382b3d1-c03c-4318-acc2-7fd3af8ec7c4.mp4"),
    ORANGE_2("https://premium.wallstcn.com/5eca2f7c-40ee-4c78-841f-77204a47a667.mp4"),
    ORANGE_3("https://premium.wallstcn.com/efb0b26c-6b87-4ed8-8be9-a65d7fbb38f0.mp4"),
    ORANGE_4("https://premium.wallstcn.com/95f8365f-2c2a-4526-b5ce-8fcd9b8583b6.mp4"),
    ORANGE_5(Config.ROOT + "orange5.mp4");

    public final String url;

    Video(String url) {
        this.url = url;
    }

    private class Config {
        private static final String ROOT = "https://raw.githubusercontent.com/danikula/AndroidVideoCache/master/files/";
    }
}
